package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-epoch inbox/outbox continuity under independently persisted local updates. */
class HydrologyMixedResolutionLedgerContinuityV2Test {
    @TempDir Path temp;
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(2, 4, x, y);
    }
    private static HydrologyMixedResolutionDischargeV2.Node node(long x, long y,
            double volume, GlobalHydrologyAddressV2.Sample next, boolean terminal) {
        return new HydrologyMixedResolutionDischargeV2.Node(at(x, y), volume, next, terminal);
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint initial() {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(0, "initial",
            new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()));
    }

    @Test void receiveAndAdvanceRetainPendingAndAcknowledgedOutgoingWater()
            throws IOException {
        Path file = temp.resolve("mixed.bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file, initial());
        var queued = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            file, initial(), "epoch1", List.of(
                node(4, 4, 7, at(5, 4), false),
                node(4, 5, 11, at(5, 5), false)), Set.of(), 2).checkpoint();
        assertEquals(2, queued.outbox().size());
        var acknowledgedEntry = queued.outbox().entrySet().iterator().next();
        var acked = HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
            file, queued, acknowledgedEntry.getKey(), acknowledgedEntry.getValue());
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.pending(acked).size());

        var incoming = new HydrologyMixedResolutionEpochV2.Receipt(2, at(6, 4), at(5, 4));
        var received = HydrologyMixedResolutionCheckpointStoreV2.receive(
            file, acked, incoming, 4);
        assertEquals(acked.outbox(), received.outbox());
        assertEquals(acked.acknowledged(), received.acknowledged());
        assertEquals(received, HydrologyMixedResolutionCheckpointStoreV2.load(file));

        var advanced = HydrologyMixedResolutionCheckpointStoreV2.advance(
            file, received, "epoch2", List.of(node(5, 4, 3, null, true)),
            Set.of(at(5, 4)));
        assertEquals(7.0, advanced.flow().terminalOutflow().get(at(5, 4)));
        assertEquals(1, advanced.checkpoint().inbox().delivered().size());
        assertEquals(acked.outbox(), advanced.checkpoint().outbox());
        assertEquals(acked.acknowledged(), advanced.checkpoint().acknowledged());
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.pending(
            HydrologyMixedResolutionCheckpointStoreV2.load(file)).size());

        // A retry after a restart cannot inject the already consumed water.
        var replay = HydrologyMixedResolutionCheckpointStoreV2.receive(
            file, HydrologyMixedResolutionCheckpointStoreV2.load(file), incoming, 4);
        var next = HydrologyMixedResolutionCheckpointStoreV2.advance(
            file, replay, "epoch3", List.of(node(5, 4, 3, null, true)),
            Set.of(at(5, 4)));
        assertEquals(3.0, next.flow().terminalOutflow().get(at(5, 4)));
        assertEquals(acked.outbox(), next.checkpoint().outbox());
        assertEquals(acked.acknowledged(), next.checkpoint().acknowledged());
    }

    @Test void plainAdvanceThenAnotherQueuePreservesEarlierFrontier()
            throws IOException {
        Path file = temp.resolve("frontier.bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file, initial());
        var first = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            file, initial(), "one", List.of(node(4, 4, 7, at(5, 4), false)),
            Set.of(), 2).checkpoint();
        var second = HydrologyMixedResolutionCheckpointStoreV2.advance(
            file, first, "two", List.of(node(7, 4, 3, null, true)),
            Set.of(at(7, 4))).checkpoint();
        assertEquals(first.outbox(), second.outbox());
        var third = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            file, second, "three", List.of(node(8, 4, 5, at(9, 4), false)),
            Set.of(), 4).checkpoint();
        assertEquals(2, HydrologyMixedResolutionCheckpointStoreV2.pending(third).size());
        assertEquals(12.0, HydrologyMixedResolutionCheckpointStoreV2.pending(third)
            .values().stream().mapToDouble(Double::doubleValue).sum());
        assertEquals(third, HydrologyMixedResolutionCheckpointStoreV2.load(file));
    }
}
