package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Receiver ownership must be checked before any durable water transfer. */
class HydrologyMixedResolutionVerifiedTransferV2Test {
    @TempDir Path temp;
    private static GlobalHydrologyAddressV2.Sample coarse(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(2, 4, x, y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(3, 4, x, y);
    }
    private static HydrologyMixedResolutionDischargeV2.Node node(
            GlobalHydrologyAddressV2.Sample at) {
        return new HydrologyMixedResolutionDischargeV2.Node(at, 0, null, true);
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint initial() {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(0, "initial",
            new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()));
    }
    private HydrologyMixedResolutionEpochV2.Receipt queued(Path sender) throws IOException {
        HydrologyMixedResolutionCheckpointStoreV2.initialize(sender, initial());
        var checkpoint = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            sender, initial(), "sender:1", List.of(
                new HydrologyMixedResolutionDischargeV2.Node(
                    coarse(4, 4), 7, fine(10, 8), false)), Set.of(), 1).checkpoint();
        return HydrologyMixedResolutionCheckpointStoreV2.pending(checkpoint)
            .keySet().iterator().next();
    }

    @Test void wrongRegionRejectedBeforeAnyReceiptOrAcknowledgment() throws IOException {
        Path sender = temp.resolve("sender.bin"), receiver = temp.resolve("receiver.bin");
        var receipt = queued(sender);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(receiver, initial());
        var beforeSender = HydrologyMixedResolutionCheckpointStoreV2.load(sender);
        var beforeReceiver = HydrologyMixedResolutionCheckpointStoreV2.load(receiver);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transferToVerifiedDestination(
                sender, receiver, receipt, List.of(node(coarse(9, 4)))));
        assertEquals(beforeSender, HydrologyMixedResolutionCheckpointStoreV2.load(sender));
        assertEquals(beforeReceiver, HydrologyMixedResolutionCheckpointStoreV2.load(receiver));
    }

    @Test void coarseAliasOfFineDestinationReceivesExactlyOnce() throws IOException {
        Path sender = temp.resolve("sender.bin"), receiver = temp.resolve("receiver.bin");
        var receipt = queued(sender);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(receiver, initial());
        var first = HydrologyMixedResolutionLocalTransferV2.transferToVerifiedDestination(
            sender, receiver, receipt, List.of(node(coarse(5, 4))));
        assertTrue(first.newlyAcknowledged());
        assertEquals(7.0, first.receiver().inbox().receipts().get(receipt));
        var retry = HydrologyMixedResolutionLocalTransferV2.transferToVerifiedDestination(
            sender, receiver, receipt, List.of(node(fine(10, 8))));
        assertFalse(retry.newlyAcknowledged());
        assertEquals(1, retry.receiver().inbox().receipts().size());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.pending(retry.sender()).isEmpty());
    }

    @Test void duplicatedPhysicalOwnerCannotPretendToBeTwoReceiverCells()
            throws IOException {
        Path sender = temp.resolve("sender.bin"), receiver = temp.resolve("receiver.bin");
        var receipt = queued(sender);
        HydrologyMixedResolutionCheckpointStoreV2.initialize(receiver, initial());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transferToVerifiedDestination(
                sender, receiver, receipt, List.of(
                    node(coarse(5, 4)), node(fine(10, 8)))));
        assertEquals(initial(), HydrologyMixedResolutionCheckpointStoreV2.load(receiver));
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.pending(
            HydrologyMixedResolutionCheckpointStoreV2.load(sender)).size());
    }

    @Test void symlinkAliasCannotDispatchWaterBackIntoSender() throws IOException {
        Path sender = temp.resolve("sender.bin"), alias = temp.resolve("alias.bin");
        var receipt = queued(sender);
        try {
            Files.createSymbolicLink(alias, sender.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            // Some test environments disallow symlinks; the core ownership
            // and exact-path self-transfer guards are exercised elsewhere.
            return;
        }
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionLocalTransferV2.transfer(sender, alias, receipt));
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.pending(
            HydrologyMixedResolutionCheckpointStoreV2.load(sender)).size());
    }
}
