package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentLoadedRegionsV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample A = at(0, 2),
        B = at(1, 2), C = at(0, 3), D = at(1, 3);

    private static HydrologySedimentReceiptInboxV2.Receipt transfer(
            long epoch, GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(epoch, epoch + 1, A, to);
    }

    private Path sender(Map<HydrologySedimentReceiptInboxV2.Receipt, Double> receipts)
            throws IOException {
        Path file = directory.resolve("sender.checkpoint");
        var initial = HydrologySedimentSenderOutboxV2.initialize(file);
        double total = receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(file, initial,
            new HydrologySedimentFrontierTransfersV2.Outgoing(receipts, total));
        return file;
    }

    private Path receiver(String name, long epoch) throws IOException {
        Path file = directory.resolve(name + ".checkpoint");
        HydrologyCheckpointStoreV2.save(file, HydrologyCheckpointV2.capture(
            epoch, HydrologySedimentCheckpointV2.initialWorld("test-world"),
            new HydrologyEpochInboxV2()));
        return file;
    }

    private static HydrologySedimentLoadedRegionsV2.Receiver region(
            Path file, GlobalHydrologyAddressV2.Sample vertex) {
        return new HydrologySedimentLoadedRegionsV2.Receiver(file, List.of(vertex));
    }

    @Test void loadedNeighborsReceiveOnceAndUnloadedDestinationStaysPending()
            throws IOException {
        var west = transfer(0, B);
        var east = transfer(1, C);
        var unloaded = transfer(2, D);
        Path source = sender(Map.of(west, 2.0, east, 3.5, unloaded, 7.0));
        Path left = receiver("left", 0), right = receiver("right", 0);
        var loaded = Map.of("z-east", region(right, C), "a-west", region(left, B));

        var first = HydrologySedimentLoadedRegionsV2.deliverLoaded(source, loaded);
        assertEquals(List.of("a-west", "z-east"),
            new ArrayList<>(first.deliveries().keySet()));
        assertEquals(2, first.newlyReceived());
        assertEquals(2, first.newlyAcknowledged());
        assertEquals(5.5, first.newlyReceivedMass(), 0.0);
        var journal = HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(source));
        assertEquals(Set.of(west, east), journal.acknowledged());
        assertEquals(3, journal.receipts().size());

        var retry = HydrologySedimentLoadedRegionsV2.deliverLoaded(source,
            Map.of("a-west", region(left, B), "z-east", region(right, C)));
        assertEquals(0, retry.newlyReceived());
        assertEquals(0, retry.newlyAcknowledged());
        assertEquals(0.0, retry.newlyReceivedMass(), 0.0);
    }

    @Test void sharedVertexAcrossDifferentResolutionsFailsBeforeAnyWrite()
            throws IOException {
        var receipt = transfer(0, B);
        Path source = sender(Map.of(receipt, 2.0));
        Path left = receiver("left", 0), right = receiver("right", 0);
        var alias = new GlobalHydrologyAddressV2.Sample(2, 4, 4, 8);
        var beforeSender = HydrologyCheckpointStoreV2.load(source);
        var beforeLeft = HydrologyCheckpointStoreV2.load(left);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentLoadedRegionsV2.deliverLoaded(source,
                Map.of("left", region(left, B), "right", region(right, alias))));
        assertEquals(beforeSender, HydrologyCheckpointStoreV2.load(source));
        assertEquals(beforeLeft, HydrologyCheckpointStoreV2.load(left));
    }

    @Test void closedSecondRegionFailsPreflightWithoutPartialDelivery()
            throws IOException {
        Path source = sender(Map.of(transfer(0, B), 2.0, transfer(1, C), 3.0));
        Path left = receiver("left", 0), right = receiver("right", 3);
        var senderBefore = HydrologyCheckpointStoreV2.load(source);
        var leftBefore = HydrologyCheckpointStoreV2.load(left);
        assertThrows(IllegalStateException.class, () ->
            HydrologySedimentLoadedRegionsV2.deliverLoaded(source,
                Map.of("a", region(left, B), "z", region(right, C))));
        assertEquals(senderBefore, HydrologyCheckpointStoreV2.load(source));
        assertEquals(leftBefore, HydrologyCheckpointStoreV2.load(left));
    }

    @Test void duplicateReceiverFileFailsPreflight() throws IOException {
        Path source = sender(Map.of(transfer(0, B), 2.0));
        Path target = receiver("shared", 0);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentLoadedRegionsV2.deliverLoaded(source,
                Map.of("one", region(target, B), "two", region(target, C))));
    }

    @Test void emptyLoadedSetDoesNotAcknowledgeUnloadedSediment() throws IOException {
        var receipt = transfer(0, B);
        Path source = sender(Map.of(receipt, 4.0));
        var result = HydrologySedimentLoadedRegionsV2.deliverLoaded(source, Map.of());
        assertTrue(result.deliveries().isEmpty());
        assertEquals(0, result.newlyReceived());
        assertTrue(HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(source)).acknowledged().isEmpty());
    }
}
