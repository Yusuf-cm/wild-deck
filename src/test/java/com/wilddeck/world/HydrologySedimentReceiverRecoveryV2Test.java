package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentReceiverRecoveryV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A = at(0, 2), B = at(1, 2), D = at(1, 3);

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(
            long source, long arrival, GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(source, arrival, from, to);
    }

    private static HydrologySedimentReceiptInboxV2.Receipt r(long epoch) {
        return receipt(epoch, epoch + 1, A, B);
    }

    private static HydrologySedimentFrontierTransfersV2.Outgoing outgoing(
            Map<HydrologySedimentReceiptInboxV2.Receipt, Double> receipts) {
        double total = receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        return new HydrologySedimentFrontierTransfersV2.Outgoing(receipts, total);
    }

    private Path sender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt, Double> receipts)
            throws IOException {
        Path path = directory.resolve(name + ".sender");
        var initial = HydrologySedimentSenderOutboxV2.initialize(path);
        HydrologySedimentSenderOutboxV2.appendDurable(path, initial, outgoing(receipts));
        return path;
    }

    private Path receiver(String name, long epoch) throws IOException {
        Path path = directory.resolve(name + ".receiver");
        var initial = HydrologyCheckpointV2.capture(epoch,
            HydrologySedimentCheckpointV2.initialWorld("test-world"),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path, initial);
        return path;
    }

    @Test void twoDurableSendersRecoverOneConfluenceWithoutDoubleCounting()
            throws IOException {
        var westReceipt = r(0);
        var northReceipt = receipt(0, 1, D, B);
        Path west = sender("west", Map.of(westReceipt, 2.0));
        Path north = sender("north", Map.of(northReceipt, 3.0));
        Path target = receiver("confluence", 0);
        var senders = Map.of("west", west, "north", north);

        var repaired = HydrologySedimentReceiverRecoveryV2.repairBatch(
            target, senders, List.of(B));
        assertEquals(2, repaired.restoredCount());
        assertEquals(5.0, repaired.restoredMass(), 0.0);
        assertEquals(Map.of(westReceipt, 2.0, northReceipt, 3.0),
            HydrologySedimentCheckpointV2.decode(
                repaired.checkpoint().worldState()).sediment().receipts());

        var audit = HydrologySedimentCrossRegionAuditV2.reconcileAllDurable(
            senders, Map.of("confluence", target));
        assertEquals(5.0, audit.awaitingMass(), 0.0);
        assertEquals(0.0, audit.inTransitMass(), 0.0);
        assertEquals(2, audit.awaitingCount());

        var replay = HydrologySedimentReceiverRecoveryV2.repairBatch(
            target, Map.of("north", north, "west", west), List.of(B));
        assertEquals(0, replay.restoredCount());
        assertEquals(0.0, replay.restoredMass(), 0.0);
        assertEquals(repaired.checkpoint(), replay.checkpoint());
    }

    @Test void senderReceiptsForOtherRegionsRemainInTransit() throws IOException {
        var local = r(0);
        var other = receipt(2, 3, A, at(0, 3));
        Path west = sender("multi-destination", Map.of(local, 4.0, other, 7.0));
        Path target = receiver("owned-only", 0);

        var repaired = HydrologySedimentReceiverRecoveryV2.repairBatch(
            target, Map.of("west", west), List.of(B));
        assertEquals(1, repaired.restoredCount());
        assertEquals(4.0, repaired.restoredMass(), 0.0);
        assertFalse(HydrologySedimentCheckpointV2.decode(
            repaired.checkpoint().worldState()).sediment().receipts().containsKey(other));
        assertEquals(2, HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(west)).receipts().size());
    }

    @Test void crossJournalSourceForkAndDuplicateTransferRejectEntireBatch()
            throws IOException {
        Path west = sender("fork-west", Map.of(r(0), 2.0));
        Path duplicate = sender("fork-duplicate", Map.of(r(0), 2.0));
        Path fork = sender("fork-other", Map.of(
            receipt(0, 1, A, at(0, 3)), 2.0));
        Path target = receiver("fork-target", 0);
        var before = HydrologyCheckpointStoreV2.load(target);

        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentReceiverRecoveryV2.repairBatch(target,
                Map.of("west", west, "duplicate", duplicate), List.of(B)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentReceiverRecoveryV2.repairBatch(target,
                Map.of("west", west, "fork", fork), List.of(B)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void duplicatePhysicalReceiverOwnershipRejectsBeforeWrite()
            throws IOException {
        Path sender = sender("ownership", Map.of(r(0), 2.0));
        Path target = receiver("ownership-target", 0);
        var before = HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentReceiverRecoveryV2.repairBatch(
                target, Map.of("west", sender), List.of(B, B)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void closedReceiverEpochCannotBeRewrittenByRecoveredSediment()
            throws IOException {
        Path sender = sender("late", Map.of(r(0), 2.0));
        Path target = receiver("closed", 2);
        var before = HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalStateException.class, () ->
            HydrologySedimentReceiverRecoveryV2.repairBatch(
                target, Map.of("west", sender), List.of(B)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void mismatchedExistingReceiverMassFailsWithoutMutation()
            throws IOException {
        Path sender = sender("conflict", Map.of(r(0), 2.0));
        Path target = receiver("conflict", 0);
        var initial = HydrologyCheckpointStoreV2.load(target);
        var wrong = HydrologySedimentCheckpointV2.receive(
            target, initial, r(0), 3.0);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentReceiverRecoveryV2.repairBatch(
                target, Map.of("west", sender), List.of(B)));
        assertEquals(wrong, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void lostReceiverBackupCanBeRepairedAfterSenderAcknowledgment()
            throws IOException {
        Path sender = sender("rollback-sender", Map.of(r(0), 5.0));
        Path target = receiver("rollback-target", 0);
        Path backup = directory.resolve("receiver-backup.chk");
        Files.copy(target, backup);
        var initial = HydrologyCheckpointStoreV2.load(target);
        HydrologySedimentCheckpointV2.receive(target, initial, r(0), 5.0);
        var journal = HydrologyCheckpointStoreV2.load(sender);
        var acknowledged = HydrologySedimentSenderOutboxV2.acknowledgeDurable(
            sender, journal, r(0), target);

        // Simulate disaster recovery from a valid but older receiver checkpoint.
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        var repaired = HydrologySedimentReceiverRecoveryV2.repairBatch(
            target, Map.of("west", sender), List.of(B));
        assertEquals(1, repaired.restoredCount());
        assertEquals(5.0, repaired.restoredMass(), 0.0);
        assertEquals(acknowledged, HydrologyCheckpointStoreV2.load(sender));
        assertTrue(HydrologySedimentSenderOutboxV2.replayMissingDurable(
            sender, target).isEmpty());
    }

    @Test void missingSenderCheckpointFailsClosed() throws IOException {
        Path target = receiver("missing-sender", 0);
        var before = HydrologyCheckpointStoreV2.load(target);
        assertThrows(IOException.class, () ->
            HydrologySedimentReceiverRecoveryV2.repairBatch(target,
                Map.of("west", directory.resolve("not-present.chk")), List.of(B)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void seededRecoveryConservesMassAcrossRestarts() throws IOException {
        var random = new Random(713);
        var transfers = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        double total = 0;
        for (int i = 0; i < 80; i++) {
            double amount = 1 + random.nextInt(100);
            transfers.put(r(i), amount);
            total += amount;
        }
        Path sender = sender("seeded", transfers);
        Path target = receiver("seeded", 0);
        var result = HydrologySedimentReceiverRecoveryV2.repairBatch(
            target, Map.of("west", sender), List.of(B));
        assertEquals(80, result.restoredCount());
        assertEquals(total, result.restoredMass(), 0.0);
        assertEquals(80, HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(target).worldState()).sediment().receipts().size());
        assertEquals(0, HydrologySedimentReceiverRecoveryV2.repairBatch(
            target, Map.of("west", sender), List.of(B)).restoredCount());
    }
}
