package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionDispatchBatchV2Test {
    @TempDir Path temp;

    private static GlobalHydrologyAddressV2.Sample coarse(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(2, 4, x, y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(3, 4, x, y);
    }
    private static HydrologyMixedResolutionDischargeV2.Node source(
            GlobalHydrologyAddressV2.Sample at, double amount,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologyMixedResolutionDischargeV2.Node(at, amount, to, false);
    }
    private static HydrologyMixedResolutionDischargeV2.Node mouth(
            GlobalHydrologyAddressV2.Sample at, double amount) {
        return new HydrologyMixedResolutionDischargeV2.Node(at, amount, null, true);
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint initial() {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(0, "initial",
            new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()));
    }
    private HydrologyMixedResolutionCheckpointStoreV2.Checkpoint queue(
            Path file, List<HydrologyMixedResolutionDischargeV2.Node> graph)
            throws IOException {
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file, initial());
        return HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            file, initial(), "sender:1", graph, Set.of(), 1).checkpoint();
    }

    @Test void confluenceDispatchesOnlyLoadedRegionAndConservesWaterAcrossRestart()
            throws IOException {
        Path sender = temp.resolve("sender.bin"), receiver = temp.resolve("receiver.bin");
        var queued = queue(sender, List.of(
            source(coarse(4, 4), 7, fine(10, 8)),
            source(coarse(5, 5), 11, coarse(5, 4)),
            source(coarse(7, 4), 13, coarse(8, 4))));
        HydrologyMixedResolutionCheckpointStoreV2.initialize(receiver, initial());

        var batch = HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(
            sender, receiver, List.of(mouth(coarse(5, 4), 3)));
        assertEquals(2, batch.newlyAcknowledged().size());
        assertEquals(2, batch.receiver().inbox().receipts().size());
        assertEquals(1, batch.stillPending().size());
        assertEquals(13.0, batch.stillPending().values().iterator().next());
        assertEquals(2, batch.sender().acknowledged().size());
        assertEquals(3, batch.sender().outbox().size());

        var repeat = HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(
            sender, receiver, List.of(mouth(fine(10, 8), 3)));
        assertTrue(repeat.newlyAcknowledged().isEmpty());
        assertEquals(2, repeat.receiver().inbox().receipts().size());
        assertEquals(1, repeat.stillPending().size());

        var result = HydrologyMixedResolutionCheckpointStoreV2.advance(
            receiver, HydrologyMixedResolutionCheckpointStoreV2.load(receiver),
            "receiver:1", List.of(mouth(coarse(5, 4), 3)), Set.of(coarse(5, 4)));
        assertEquals(21.0, result.flow().terminalOutflow().get(coarse(5, 4)));
        assertEquals(2, result.checkpoint().inbox().delivered().size());
        assertEquals(13.0, HydrologyMixedResolutionCheckpointStoreV2.pending(
            HydrologyMixedResolutionCheckpointStoreV2.load(sender))
            .values().iterator().next());
        assertEquals(3, queued.outbox().size());
    }

    @Test void laterLoadingNeighborReceivesRemainingReceiptExactlyOnce() throws IOException {
        Path sender = temp.resolve("sender.bin"), a = temp.resolve("a.bin"),
            b = temp.resolve("b.bin");
        queue(sender, List.of(source(coarse(4, 4), 7, coarse(5, 4)),
            source(coarse(7, 4), 13, coarse(8, 4))));
        HydrologyMixedResolutionCheckpointStoreV2.initialize(a, initial());
        HydrologyMixedResolutionCheckpointStoreV2.initialize(b, initial());
        var first = HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(
            sender, a, List.of(mouth(coarse(5, 4), 0)));
        assertEquals(1, first.stillPending().size());
        var second = HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(
            sender, b, List.of(mouth(fine(16, 8), 0)));
        assertEquals(1, second.newlyAcknowledged().size());
        assertTrue(second.stillPending().isEmpty());
        assertEquals(13.0, second.receiver().inbox().receipts().values().iterator().next());
        assertTrue(HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(
            sender, b, List.of(mouth(coarse(8, 4), 0))).newlyAcknowledged().isEmpty());
    }

    @Test void duplicatePhysicalReceiverOwnershipRejectedBeforeMutation() throws IOException {
        Path sender = temp.resolve("sender.bin"), receiver = temp.resolve("receiver.bin");
        var queued = queue(sender, List.of(source(coarse(4, 4), 7, coarse(5, 4))));
        HydrologyMixedResolutionCheckpointStoreV2.initialize(receiver, initial());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(sender, receiver,
                List.of(mouth(coarse(5, 4), 0), mouth(fine(10, 8), 0))));
        assertEquals(queued, HydrologyMixedResolutionCheckpointStoreV2.load(sender));
        assertEquals(initial(), HydrologyMixedResolutionCheckpointStoreV2.load(receiver));
    }

    @Test void missingReceiverCannotAcknowledgeAnyWater() throws IOException {
        Path sender = temp.resolve("sender.bin"), missing = temp.resolve("missing.bin");
        var queued = queue(sender, List.of(source(coarse(4, 4), 7, coarse(5, 4))));
        assertThrows(IOException.class, () ->
            HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(sender, missing,
                List.of(mouth(coarse(5, 4), 0))));
        assertEquals(queued, HydrologyMixedResolutionCheckpointStoreV2.load(sender));
    }

    @Test void selfDispatchRejectedEvenWhenNoWaterIsPending() throws IOException {
        Path file = temp.resolve("checkpoint.bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file, initial());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionDispatchBatchV2.dispatchAvailable(
                file, file, List.of(mouth(coarse(5, 4), 0))));
    }
}
