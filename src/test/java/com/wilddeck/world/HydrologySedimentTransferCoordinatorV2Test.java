package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentTransferCoordinatorV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }

    private static final GlobalHydrologyAddressV2.Sample
        A = at(0, 2), B = at(1, 2), D = at(1, 3), C = at(0, 3);

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(
            long source, long arrival, GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(source, arrival, from, to);
    }

    private static HydrologySedimentReceiptInboxV2.Receipt r(long epoch) {
        return receipt(epoch, epoch + 1, A, B);
    }

    private Path sender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt, Double> receipts)
            throws IOException {
        Path path = directory.resolve(name + ".sender");
        var first = HydrologySedimentSenderOutboxV2.initialize(path);
        double total = receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(path, first,
            new HydrologySedimentFrontierTransfersV2.Outgoing(receipts, total));
        return path;
    }

    private Path receiver(String name, long epoch) throws IOException {
        Path path = directory.resolve(name + ".receiver");
        HydrologyCheckpointStoreV2.save(path, HydrologyCheckpointV2.capture(
            epoch, HydrologySedimentCheckpointV2.initialWorld("test-world"),
            new HydrologyEpochInboxV2()));
        return path;
    }

    private static HydrologySedimentSenderOutboxV2.Snapshot outbox(Path file)
            throws IOException {
        return HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(file));
    }

    private static HydrologySedimentReceiptInboxV2.Snapshot inbox(Path file)
            throws IOException {
        return HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(file).worldState()).sediment();
    }

    @Test void durableDeliveryAcknowledgesOnlyAfterReceiverCommitAndReplaysOnce()
            throws IOException {
        var transfer = r(0);
        Path source = sender("simple", Map.of(transfer, 4.5));
        Path target = receiver("simple", 0);

        var delivered = HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B));
        assertEquals(1, delivered.newlyReceived());
        assertEquals(1, delivered.newlyAcknowledged());
        assertEquals(4.5, delivered.newlyReceivedMass(), 0.0);
        assertEquals(4.5, inbox(target).receipts().get(transfer), 0.0);
        assertTrue(outbox(source).acknowledged().contains(transfer));

        var replay = HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B));
        assertEquals(0, replay.newlyReceived());
        assertEquals(0, replay.newlyAcknowledged());
        assertEquals(0.0, replay.newlyReceivedMass(), 0.0);
        assertEquals(delivered.receiver(), replay.receiver());
        assertEquals(delivered.sender(), replay.sender());
    }

    @Test void unrelatedUnloadedDestinationRemainsUnacknowledged()
            throws IOException {
        var local = r(0);
        var remote = receipt(1, 2, A, C);
        Path source = sender("two-targets", Map.of(local, 2.0, remote, 7.0));
        Path target = receiver("one-target", 0);

        var delivered = HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B));
        assertEquals(1, delivered.newlyReceived());
        assertEquals(Map.of(local, 2.0), inbox(target).receipts());
        assertEquals(Set.of(local), outbox(source).acknowledged());
        assertEquals(2, outbox(source).receipts().size());
    }

    @Test void multiSenderConfluenceRecoveryAcknowledgesBothAndConservesMass()
            throws IOException {
        var west = r(0);
        var north = receipt(0, 1, D, B);
        Path sourceWest = sender("west", Map.of(west, 2.0));
        Path sourceNorth = sender("north", Map.of(north, 3.0));
        Path target = receiver("confluence", 0);
        var senders = Map.of("west", sourceWest, "north", sourceNorth);

        var result = HydrologySedimentTransferCoordinatorV2.recoverAndAcknowledge(
            target, senders, List.of(B));
        assertEquals(2, result.newlyReceived());
        assertEquals(5.0, result.newlyReceivedMass(), 0.0);
        assertEquals(2, inbox(target).receipts().size());
        assertTrue(outbox(sourceWest).acknowledged().contains(west));
        assertTrue(outbox(sourceNorth).acknowledged().contains(north));
        var audit = HydrologySedimentCrossRegionAuditV2.reconcileAllDurable(
            senders, Map.of("confluence", target));
        assertEquals(5.0, audit.awaitingMass(), 0.0);
        assertEquals(0.0, audit.inTransitMass(), 0.0);

        var retry = HydrologySedimentTransferCoordinatorV2.recoverAndAcknowledge(
            target, Map.of("north", sourceNorth, "west", sourceWest), List.of(B));
        assertEquals(0, retry.newlyReceived());
        assertEquals(0.0, retry.newlyReceivedMass(), 0.0);
        assertEquals(result.receiver(), retry.receiver());
        assertEquals(result.senders(), retry.senders());
    }

    @Test void missingReceiverFailsWithoutAcknowledgingSender() throws IOException {
        var transfer = r(0);
        Path source = sender("missing", Map.of(transfer, 2.0));
        var original = HydrologyCheckpointStoreV2.load(source);
        assertThrows(IOException.class, () ->
            HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
                source, directory.resolve("absent.receiver"), List.of(B)));
        assertEquals(original, HydrologyCheckpointStoreV2.load(source));
        assertTrue(outbox(source).acknowledged().isEmpty());
    }

    @Test void closedEpochFailsBeforeReceiverOrSenderMutation() throws IOException {
        Path source = sender("late", Map.of(r(0), 2.0));
        Path target = receiver("late", 2);
        var beforeSender = HydrologyCheckpointStoreV2.load(source);
        var beforeReceiver = HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalStateException.class, () ->
            HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
                source, target, List.of(B)));
        assertEquals(beforeSender, HydrologyCheckpointStoreV2.load(source));
        assertEquals(beforeReceiver, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void conflictingReceiverMassFailsWithoutAnyWrite() throws IOException {
        var transfer = r(0);
        Path source = sender("conflict", Map.of(transfer, 2.0));
        Path target = receiver("conflict", 0);
        var before = HydrologyCheckpointStoreV2.load(target);
        HydrologySedimentCheckpointV2.receive(target, before, transfer, 9.0);
        var senderBefore = HydrologyCheckpointStoreV2.load(source);
        var receiverBefore = HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
                source, target, List.of(B)));
        assertEquals(senderBefore, HydrologyCheckpointStoreV2.load(source));
        assertEquals(receiverBefore, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void receiverRollbackAfterAcknowledgmentIsRecoveredAndReacknowledged()
            throws IOException {
        var transfer = r(0);
        Path source = sender("restore", Map.of(transfer, 6.0));
        Path target = receiver("restore", 0);
        Path backup = directory.resolve("restore.backup");
        Files.copy(target, backup);
        HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B));
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        var repaired = HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B));
        assertEquals(1, repaired.newlyReceived());
        assertEquals(0, repaired.newlyAcknowledged());
        assertEquals(6.0, repaired.newlyReceivedMass(), 0.0);
        assertEquals(6.0, inbox(target).receipts().get(transfer), 0.0);
        assertTrue(outbox(source).acknowledged().contains(transfer));
    }

    @Test void geographicAliasAndSameFileAreRejectedBeforeWrite() throws IOException {
        Path source = sender("alias", Map.of(r(0), 2.0));
        Path target = receiver("alias", 0);
        var before = HydrologyCheckpointStoreV2.load(target);
        var alias = new GlobalHydrologyAddressV2.Sample(2, 4, 4, 8);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
                source, target, List.of(B, alias)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(target));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
                source, source, List.of(B)));
    }

    @Test void duplicatePhysicalSenderJournalFailsBeforeRecovery() throws IOException {
        Path source = sender("duplicate", Map.of(r(0), 2.0));
        Path target = receiver("duplicate", 0);
        var before = HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentTransferCoordinatorV2.recoverAndAcknowledge(
                target, Map.of("a", source, "b", source), List.of(B)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(target));
    }


    @Test void crashAfterReceiverCommitBeforeAcknowledgmentCanRetry()
            throws IOException {
        var transfer = r(0);
        Path source = sender("crash-gap", Map.of(transfer, 8.0));
        Path target = receiver("crash-gap", 0);
        var before = HydrologyCheckpointStoreV2.load(target);
        var received = HydrologySedimentCheckpointV2.receiveBatch(
            target, before, Map.of(transfer, 8.0));
        assertTrue(outbox(source).acknowledged().isEmpty());

        var retry = HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B));
        assertEquals(0, retry.newlyReceived());
        assertEquals(1, retry.newlyAcknowledged());
        assertEquals(0.0, retry.newlyReceivedMass(), 0.0);
        assertEquals(received, retry.receiver());
        assertEquals(Set.of(transfer), outbox(source).acknowledged());
    }

    @Test void partiallyAcknowledgedMultiSenderRecoveryFinishesIdempotently()
            throws IOException {
        var west = r(0);
        var north = receipt(0, 1, D, B);
        Path sourceWest = sender("partial-west", Map.of(west, 2.0));
        Path sourceNorth = sender("partial-north", Map.of(north, 3.0));
        Path target = receiver("partial", 0);
        var initial = HydrologyCheckpointStoreV2.load(target);
        HydrologySedimentCheckpointV2.receiveBatch(
            target, initial, Map.of(west, 2.0, north, 3.0));
        var senderBefore = HydrologyCheckpointStoreV2.load(sourceWest);
        HydrologySedimentSenderOutboxV2.acknowledgeBatchDurable(
            sourceWest, senderBefore, List.of(west), target);
        assertTrue(outbox(sourceNorth).acknowledged().isEmpty());

        var result = HydrologySedimentTransferCoordinatorV2.recoverAndAcknowledge(
            target, Map.of("west", sourceWest, "north", sourceNorth), List.of(B));
        assertEquals(0, result.newlyReceived());
        assertEquals(0.0, result.newlyReceivedMass(), 0.0);
        assertEquals(Set.of(west), outbox(sourceWest).acknowledged());
        assertEquals(Set.of(north), outbox(sourceNorth).acknowledged());
        assertEquals(2, inbox(target).receipts().size());
    }

    @Test void seededDeliveryConservesAllReceiptsAcrossRetries() throws IOException {
        var random = new Random(1942);
        var receipts = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        double expectedMass = 0;
        for (int i = 0; i < 70; i++) {
            double amount = 1 + random.nextInt(100);
            receipts.put(r(i), amount);
            expectedMass += amount;
        }
        Path source = sender("seeded", receipts);
        Path target = receiver("seeded", 0);
        var result = HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B));
        assertEquals(70, result.newlyReceived());
        assertEquals(70, result.newlyAcknowledged());
        assertEquals(expectedMass, result.newlyReceivedMass(), 0.0);
        assertEquals(70, inbox(target).receipts().size());
        assertEquals(70, outbox(source).acknowledged().size());
        assertEquals(0, HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
            source, target, List.of(B)).newlyReceived());
    }
}
