package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentLoadedNetworkV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A = at(0, 2), B = at(1, 2), C = at(0, 3), D = at(1, 3);

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(
            long epoch, GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(epoch, epoch + 1, from, to);
    }

    private Path sender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt, Double> receipts)
            throws IOException {
        Path file = directory.resolve(name + ".sender");
        var initial = HydrologySedimentSenderOutboxV2.initialize(file);
        double mass = receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(file, initial,
            new HydrologySedimentFrontierTransfersV2.Outgoing(receipts, mass));
        return file;
    }

    private Path receiver(String name, long epoch) throws IOException {
        Path file = directory.resolve(name + ".receiver");
        HydrologyCheckpointStoreV2.save(file, HydrologyCheckpointV2.capture(
            epoch, HydrologySedimentCheckpointV2.initialWorld("test-world"),
            new HydrologyEpochInboxV2()));
        return file;
    }

    private static HydrologySedimentLoadedRegionsV2.Receiver region(
            Path file, GlobalHydrologyAddressV2.Sample... vertices) {
        return new HydrologySedimentLoadedRegionsV2.Receiver(file, List.of(vertices));
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

    @Test void twoSendersTwoLoadedRegionsAndUnloadedDestinationConserveMass()
            throws IOException {
        var west = receipt(0, A, B);
        var east = receipt(1, A, C);
        var unloaded = receipt(2, A, D);
        var north = receipt(0, D, B);
        Path first = sender("first", Map.of(west, 2.0, east, 4.0, unloaded, 7.0));
        Path second = sender("second", Map.of(north, 3.0));
        Path left = receiver("left", 0), right = receiver("right", 0);
        var senders = Map.of("z-first", first, "a-second", second);
        var loaded = Map.of("z-right", region(right, C), "a-left", region(left, B));

        var preflight = HydrologySedimentLoadedNetworkV2.plan(senders, loaded);
        assertEquals(3, preflight.pendingReceipts());
        assertEquals(3, preflight.pendingAcknowledgments());
        assertEquals(9.0, preflight.pendingMass(), 0.0);
        assertEquals(16.0, preflight.before().inTransitMass(), 0.0);
        assertTrue(inbox(left).receipts().isEmpty());
        assertTrue(outbox(first).acknowledged().isEmpty());

        var delivered = HydrologySedimentLoadedNetworkV2.deliver(senders, loaded);
        assertEquals(List.of("a-left", "z-right"),
            new ArrayList<>(delivered.receivers().keySet()));
        assertEquals(3, delivered.newlyReceived());
        assertEquals(9.0, delivered.newlyReceivedMass(), 0.0);
        assertEquals(16.0, delivered.after().sentMass(), 0.0);
        assertEquals(9.0, delivered.after().awaitingMass(), 0.0);
        assertEquals(7.0, delivered.after().inTransitMass(), 0.0);
        assertEquals(Map.of(west, 2.0, north, 3.0), inbox(left).receipts());
        assertEquals(Map.of(east, 4.0), inbox(right).receipts());
        assertEquals(Set.of(west, east), outbox(first).acknowledged());
        assertEquals(Set.of(north), outbox(second).acknowledged());
        assertEquals(3, outbox(first).receipts().size());

        var retry = HydrologySedimentLoadedNetworkV2.deliver(
            Map.of("a-second", second, "z-first", first),
            Map.of("a-left", region(left, B), "z-right", region(right, C)));
        assertEquals(0, retry.newlyReceived());
        assertEquals(0.0, retry.newlyReceivedMass(), 0.0);
        assertEquals(0, HydrologySedimentLoadedNetworkV2.plan(senders, loaded)
            .pendingAcknowledgments());
    }

    @Test void sharedCrossResolutionReceiverVertexFailsBeforeAnyWrite()
            throws IOException {
        Path source = sender("ownership", Map.of(receipt(0, A, B), 2.0));
        Path left = receiver("owner-left", 0), right = receiver("owner-right", 0);
        var beforeSender = HydrologyCheckpointStoreV2.load(source);
        var beforeLeft = HydrologyCheckpointStoreV2.load(left);
        var alias = new GlobalHydrologyAddressV2.Sample(2, 4, 4, 8);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentLoadedNetworkV2.deliver(Map.of("source", source),
                Map.of("left", region(left, B), "right", region(right, alias))));
        assertEquals(beforeSender, HydrologyCheckpointStoreV2.load(source));
        assertEquals(beforeLeft, HydrologyCheckpointStoreV2.load(left));
    }

    @Test void duplicateSenderFilesAndPhysicalSourceForkFailPreflight()
            throws IOException {
        var west = receipt(0, A, B);
        Path first = sender("duplicate-one", Map.of(west, 2.0));
        Path second = sender("duplicate-two", Map.of(west, 2.0));
        Path fork = sender("fork", Map.of(receipt(0, A, C), 2.0));
        Path target = receiver("fork-target", 0);
        var before = HydrologyCheckpointStoreV2.load(target);
        var loaded = Map.of("target", region(target, B));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentLoadedNetworkV2.deliver(
                Map.of("one", first, "alias", first), loaded));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentLoadedNetworkV2.deliver(
                Map.of("one", first, "two", second), loaded));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentLoadedNetworkV2.deliver(
                Map.of("one", first, "fork", fork), loaded));
        assertEquals(before, HydrologyCheckpointStoreV2.load(target));
    }

    @Test void closedSecondRegionRejectsEntireNetworkBeforeFirstWrite()
            throws IOException {
        Path source = sender("closed", Map.of(
            receipt(0, A, B), 2.0, receipt(1, A, C), 3.0));
        Path first = receiver("open", 0), second = receiver("closed", 3);
        var beforeSource = HydrologyCheckpointStoreV2.load(source);
        var beforeFirst = HydrologyCheckpointStoreV2.load(first);
        assertThrows(IllegalStateException.class, () ->
            HydrologySedimentLoadedNetworkV2.deliver(Map.of("source", source),
                Map.of("a-open", region(first, B), "z-closed", region(second, C))));
        assertEquals(beforeSource, HydrologyCheckpointStoreV2.load(source));
        assertEquals(beforeFirst, HydrologyCheckpointStoreV2.load(first));
    }

    @Test void unprovenReceiverHistoryFailsClosedBeforeAnyWrite()
            throws IOException {
        var expected = receipt(0, A, B);
        var unknown = receipt(1, D, B);
        Path source = sender("proven", Map.of(expected, 2.0));
        Path target = receiver("unproven", 0);
        var initial = HydrologyCheckpointStoreV2.load(target);
        var poisoned = HydrologySedimentCheckpointV2.receiveBatch(
            target, initial, Map.of(unknown, 3.0));
        var beforeSource = HydrologyCheckpointStoreV2.load(source);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentLoadedNetworkV2.deliver(Map.of("source", source),
                Map.of("target", region(target, B))));
        assertEquals(poisoned, HydrologyCheckpointStoreV2.load(target));
        assertEquals(beforeSource, HydrologyCheckpointStoreV2.load(source));
    }

    @Test void receiverRestorationAfterAcknowledgmentReplaysFromDurableSenders()
            throws IOException {
        var west = receipt(0, A, B);
        var north = receipt(0, D, B);
        Path first = sender("restore-first", Map.of(west, 5.0));
        Path second = sender("restore-second", Map.of(north, 8.0));
        Path target = receiver("restore-target", 0);
        Path backup = directory.resolve("restore-backup");
        Files.copy(target, backup);
        var senders = Map.of("first", first, "second", second);
        var loaded = Map.of("target", region(target, B));
        assertEquals(2, HydrologySedimentLoadedNetworkV2.deliver(senders, loaded)
            .newlyReceived());
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        var recovered = HydrologySedimentLoadedNetworkV2.deliver(senders, loaded);
        assertEquals(2, recovered.newlyReceived());
        assertEquals(13.0, recovered.newlyReceivedMass(), 0.0);
        assertEquals(2, inbox(target).receipts().size());
        assertEquals(0, HydrologySedimentLoadedNetworkV2.plan(senders, loaded)
            .pendingAcknowledgments());
    }

    @Test void partialReceiverCommitThenRetryAcknowledgesWithoutDuplicateMass()
            throws IOException {
        var west = receipt(0, A, B);
        var east = receipt(1, A, C);
        Path source = sender("crash", Map.of(west, 2.0, east, 3.0));
        Path left = receiver("crash-left", 0), right = receiver("crash-right", 0);
        var before = HydrologyCheckpointStoreV2.load(left);
        HydrologySedimentCheckpointV2.receiveBatch(left, before, Map.of(west, 2.0));
        var loaded = Map.of("left", region(left, B), "right", region(right, C));
        var plan = HydrologySedimentLoadedNetworkV2.plan(
            Map.of("source", source), loaded);
        assertEquals(1, plan.pendingReceipts());
        assertEquals(2, plan.pendingAcknowledgments());
        var delivered = HydrologySedimentLoadedNetworkV2.deliver(
            Map.of("source", source), loaded);
        assertEquals(1, delivered.newlyReceived());
        assertEquals(3.0, delivered.newlyReceivedMass(), 0.0);
        assertEquals(5.0, delivered.after().awaitingMass(), 0.0);
        assertEquals(Set.of(west, east), outbox(source).acknowledged());
    }

    @Test void seededManyEpochsRemainConservedAndIdempotent() throws IOException {
        var random = new Random(9142);
        var west = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        var north = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        double total = 0;
        for (int epoch = 0; epoch < 60; epoch++) {
            double a = 1 + random.nextInt(100);
            double b = 1 + random.nextInt(100);
            west.put(receipt(epoch, A, B), a);
            north.put(receipt(epoch, D, C), b);
            total += a + b;
        }
        Path first = sender("seeded-west", west), second = sender("seeded-north", north);
        Path left = receiver("seeded-left", 0), right = receiver("seeded-right", 0);
        var senders = Map.of("west", first, "north", second);
        var loaded = Map.of("left", region(left, B), "right", region(right, C));
        var result = HydrologySedimentLoadedNetworkV2.deliver(senders, loaded);
        assertEquals(120, result.newlyReceived());
        assertEquals(total, result.newlyReceivedMass(), 0.0);
        assertEquals(total, result.after().awaitingMass(), 0.0);
        assertEquals(0.0, result.after().inTransitMass(), 0.0);
        assertEquals(120, result.after().sentCount());
        assertEquals(0, HydrologySedimentLoadedNetworkV2.deliver(senders, loaded)
            .newlyReceived());
    }

    @Test void unloadedNetworkDoesNotAcknowledgeAnySender() throws IOException {
        var transfer = receipt(0, A, B);
        Path source = sender("unloaded", Map.of(transfer, 4.0));
        var result = HydrologySedimentLoadedNetworkV2.deliver(
            Map.of("source", source), Map.of());
        assertEquals(0, result.newlyReceived());
        assertEquals(4.0, result.after().inTransitMass(), 0.0);
        assertTrue(outbox(source).acknowledged().isEmpty());
    }
}
