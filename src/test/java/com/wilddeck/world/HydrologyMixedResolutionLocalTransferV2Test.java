package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionLocalTransferV2Test {
    @TempDir Path temp;
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(2, 4, x, y);
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint initial() {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(0, "initial",
            new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()));
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint sender(Path file)
            throws IOException {
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file, initial());
        return HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            file, initial(), "source:1",
            List.of(new HydrologyMixedResolutionDischargeV2.Node(
                at(4, 4), 7, at(5, 4), false)), Set.of(), 1).checkpoint();
    }
    private static HydrologyMixedResolutionEpochV2.Receipt key(
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint sender) {
        return HydrologyMixedResolutionCheckpointStoreV2.pending(sender)
            .keySet().iterator().next();
    }

    @Test void transferAcceptsBeforeAckAndCanBeRepeatedAfterRestart() throws IOException {
        Path from = temp.resolve("from.bin");
        Path to = temp.resolve("to.bin");
        var initialSender = sender(from);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(to, initial());
        var receipt = key(initialSender);
        var first = HydrologyMixedResolutionLocalTransferV2.transfer(from, to, receipt);
        assertTrue(first.newlyAcknowledged());
        assertEquals(7.0, first.receiver().inbox().receipts().get(receipt));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.pending(first.sender()).isEmpty());
        var again = HydrologyMixedResolutionLocalTransferV2.transfer(from, to, receipt);
        assertFalse(again.newlyAcknowledged());
        assertEquals(first.sender(), again.sender());
        assertEquals(first.receiver(), again.receiver());
        var receiverGraph = List.of(new HydrologyMixedResolutionDischargeV2.Node(
            at(5, 4), 3, null, true));
        var consumed = HydrologyMixedResolutionCheckpointStoreV2.advance(
            to, HydrologyMixedResolutionCheckpointStoreV2.load(to),
            "destination:1", receiverGraph, Set.of(at(5, 4)));
        assertEquals(10.0, consumed.flow().terminalOutflow().get(at(5, 4)));
        assertEquals(1, consumed.checkpoint().inbox().delivered().size());
        assertFalse(HydrologyMixedResolutionLocalTransferV2.transfer(
            from, to, receipt).newlyAcknowledged());
    }

    @Test void receiverMissingThenRecoveredNeverDropsSenderWater() throws IOException {
        Path from = temp.resolve("from.bin");
        Path to = temp.resolve("missing.bin");
        var pending = sender(from);
        var receipt = key(pending);
        assertThrows(IOException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transfer(from, to, receipt));
        assertEquals(pending, HydrologyMixedResolutionCheckpointStoreV2.load(from));
        HydrologyMixedResolutionCheckpointStoreV2.initialize(to, initial());
        var delivered = HydrologyMixedResolutionLocalTransferV2.transfer(from, to, receipt);
        assertTrue(delivered.newlyAcknowledged());
        assertEquals(7.0, delivered.receiver().inbox().receipts().get(receipt));
    }

    @Test void conflictingReceiverReceiptLeavesSenderUnacknowledged() throws IOException {
        Path from = temp.resolve("from.bin");
        Path to = temp.resolve("to.bin");
        var pending = sender(from);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(to, initial());
        var receipt = key(pending);
        var badReceiver = HydrologyMixedResolutionCheckpointStoreV2.receive(
            to, initial(), receipt, 8);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transfer(from, to, receipt));
        assertEquals(pending, HydrologyMixedResolutionCheckpointStoreV2.load(from));
        assertEquals(badReceiver, HydrologyMixedResolutionCheckpointStoreV2.load(to));
    }

    @Test void cannotDispatchToSelfOrAcceptUnqueuedReceipt() throws IOException {
        Path from = temp.resolve("from.bin");
        Path to = temp.resolve("to.bin");
        var pending = sender(from);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(to, initial());
        var receipt = key(pending);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transfer(from, from, receipt));
        var other = new HydrologyMixedResolutionEpochV2.Receipt(
            1, at(7, 4), at(8, 4));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transfer(from, to, other));
    }

    @Test void acknowledgedSenderDetectsReceiverCheckpointLoss() throws IOException {
        Path from = temp.resolve("from.bin");
        Path to = temp.resolve("to.bin");
        var pending = sender(from);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(to, initial());
        var receipt = key(pending);
        HydrologyMixedResolutionLocalTransferV2.transfer(from, to, receipt);
        Files.delete(to);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(to, initial());
        assertThrows(IOException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transfer(from, to, receipt));
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.load(from)
            .acknowledged().size());
    }
}
