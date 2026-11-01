package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentTopologyGateV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A = at(0, 2), B = at(1, 2), C = at(0, 3);

    private static HydrologySedimentReceiptInboxV2.Receipt transfer(
            GlobalHydrologyAddressV2.Sample destination) {
        return new HydrologySedimentReceiptInboxV2.Receipt(0, 1, A, destination);
    }

    private Path sender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt, Double> receipts)
            throws IOException {
        Path path = directory.resolve(name + ".sender");
        var checkpoint = HydrologySedimentSenderOutboxV2.initialize(path);
        double mass = receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(path, checkpoint,
            new HydrologySedimentFrontierTransfersV2.Outgoing(receipts, mass));
        return path;
    }

    private Path receiver(String name, HydrologyEpochInboxV2 water) throws IOException {
        Path path = directory.resolve(name + ".receiver");
        HydrologyCheckpointStoreV2.save(path, HydrologyCheckpointV2.capture(
            0, HydrologySedimentCheckpointV2.initialWorld("lake-state"), water));
        return path;
    }

    private static HydrologySedimentLoadedRegionsV2.Receiver region(Path path,
            GlobalHydrologyAddressV2.Sample... vertices) {
        return new HydrologySedimentLoadedRegionsV2.Receiver(path, List.of(vertices));
    }

    private static void consumeSediment(Path path,
            GlobalHydrologyAddressV2.Sample destination) throws IOException {
        var previous = HydrologyCheckpointStoreV2.load(path);
        var state = HydrologySedimentCheckpointV2.decode(previous.worldState());
        var claimed = HydrologySedimentReceiptInboxV2.claimAvailable(
            state.sediment(), previous.epoch() + 1, List.of(destination));
        var next = new HydrologyCheckpointV2.Checkpoint(previous.epoch() + 1,
            HydrologySedimentCheckpointV2.encode(state.world(), claimed.snapshot()),
            previous.inboxState());
        HydrologyCheckpointStoreV2.saveNext(path, previous, next);
    }

    @Test void tracksInTransitThenAwaitingThenDeliveredWithoutDoubleCounting()
            throws IOException {
        Path source = sender("source", Map.of(transfer(B), 4.5));
        Path target = receiver("target", new HydrologyEpochInboxV2());
        var senders = Map.of("source", source);
        var loaded = Map.of("target", region(target, B));
        var beforeSource = HydrologyCheckpointStoreV2.load(source);
        var beforeTarget = HydrologyCheckpointStoreV2.load(target);

        var transit = HydrologySedimentTopologyGateV2.inspect(senders, loaded);
        assertEquals(1, transit.inTransit());
        assertEquals(4.5, transit.unresolvedMass(), 0.0);
        assertThrows(IllegalStateException.class,
            () -> HydrologySedimentTopologyGateV2.requireQuiescent(senders, loaded));
        assertEquals(beforeSource, HydrologyCheckpointStoreV2.load(source));
        assertEquals(beforeTarget, HydrologyCheckpointStoreV2.load(target));

        HydrologySedimentLoadedNetworkV2.deliver(senders, loaded);
        var awaiting = HydrologySedimentTopologyGateV2.inspect(senders, loaded);
        assertEquals(1, awaiting.awaiting());
        assertEquals(0, awaiting.pendingAcknowledgments());
        assertFalse(awaiting.quiescent());

        consumeSediment(target, B);
        var settled = HydrologySedimentTopologyGateV2.requireQuiescent(senders, loaded);
        assertEquals(1, settled.verified());
        assertEquals(4.5, settled.verifiedMass(), 0.0);
        assertEquals(0, settled.inTransit());
        assertEquals(0.0, settled.unresolvedMass(), 0.0);
    }

    @Test void unloadedDestinationBlocksTopologyChange() throws IOException {
        Path source = sender("unloaded", Map.of(transfer(C), 7.0));
        Path target = receiver("loaded", new HydrologyEpochInboxV2());
        var report = HydrologySedimentTopologyGateV2.inspect(
            Map.of("source", source), Map.of("loaded", region(target, B)));
        assertEquals(1, report.inTransit());
        assertEquals(7.0, report.unresolvedMass(), 0.0);
        assertFalse(report.quiescent());
    }

    @Test void receiverRestoreAfterAcknowledgmentFailsClosed() throws IOException {
        Path source = sender("restore", Map.of(transfer(B), 2.0));
        Path target = receiver("restore", new HydrologyEpochInboxV2());
        Path backup = directory.resolve("backup");
        Files.copy(target, backup);
        var senders = Map.of("source", source);
        var loaded = Map.of("target", region(target, B));
        HydrologySedimentLoadedNetworkV2.deliver(senders, loaded);
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalStateException.class,
            () -> HydrologySedimentTopologyGateV2.inspect(senders, loaded));
    }

    @Test void consumedButUnacknowledgedSedimentIsNotQuiescent()
            throws IOException {
        Path source = sender("unacknowledged", Map.of(transfer(B), 3.0));
        Path target = receiver("unacknowledged", new HydrologyEpochInboxV2());
        var original = HydrologyCheckpointStoreV2.load(target);
        HydrologySedimentCheckpointV2.receiveBatch(
            target, original, Map.of(transfer(B), 3.0));
        consumeSediment(target, B);
        var report = HydrologySedimentTopologyGateV2.inspect(
            Map.of("source", source), Map.of("target", region(target, B)));
        assertEquals(1, report.verified());
        assertEquals(1, report.pendingAcknowledgments());
        assertFalse(report.quiescent());
    }

    @Test void pendingWaterBlocksEvenWhenSedimentIsEmpty() throws IOException {
        var water = new HydrologyEpochInboxV2();
        water.receive(new HydrologyTransferLedgerV2.Key(1, A, B), 5.0);
        Path target = receiver("water", water);
        var loaded = Map.of("target", region(target, B));
        var report = HydrologySedimentTopologyGateV2.inspect(Map.of(), loaded);
        assertEquals(0, report.verified());
        assertEquals(1, report.pendingWater());
        assertThrows(IllegalStateException.class,
            () -> HydrologySedimentTopologyGateV2.requireQuiescent(Map.of(), loaded));
    }

    @Test void crossResolutionDuplicateOwnershipFailsBeforeAnyWrite()
            throws IOException {
        Path source = sender("owner", Map.of(transfer(B), 1.0));
        Path left = receiver("left", new HydrologyEpochInboxV2());
        Path right = receiver("right", new HydrologyEpochInboxV2());
        var alias = new GlobalHydrologyAddressV2.Sample(2, 4, 4, 8);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySedimentTopologyGateV2.inspect(Map.of("source", source),
                Map.of("left", region(left, B), "right", region(right, alias))));
    }
}
