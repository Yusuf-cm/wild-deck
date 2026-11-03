package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalReceiptOriginV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }

    @Test void ingressRejectsAliasedSourceWithoutDoubleCountingWater() {
        var destination = at(7, 2);
        var canonical = at(0, 2);
        var aliasedSeam = at(8, 2);
        var inbox = new HydrologyEpochInboxV2();
        assertTrue(inbox.receive(
            new HydrologyTransferLedgerV2.Key(1, canonical, destination), 3));
        // A physical origin is now protected at receipt ingress. The old
        // fixture expected both receipts to be accepted and rejected later.
        assertThrows(IllegalArgumentException.class, () -> inbox.receive(
            new HydrologyTransferLedgerV2.Key(1, aliasedSeam, destination), 3));
        assertEquals(1, inbox.snapshot().receipts().size());
        assertEquals(3, HydrologyReceiptBudgetV2.summarize(
            inbox.snapshot()).pendingVolume(), 1e-12);
    }

    @Test void sphericalSchedulerRejectsForgedSourceForkBeforeLoadingRegions() {
        var source = at(0, 2);
        var first = new HydrologyTransferLedgerV2.Key(1, source, at(7, 2));
        var fork = new HydrologyTransferLedgerV2.Key(1, source, at(1, 2));
        // Construct an untrusted snapshot without passing through the ledger.
        var forged = new HydrologyEpochInboxV2.Snapshot(
            java.util.Map.of(first, 3.0, fork, 4.0), Set.of(), Set.of());
        var loaded = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySphericalRegionSchedulerV2.loadWithPending(
                List.of(), forged, 1, 0, 8,
                region -> { loaded.incrementAndGet(); return List.of(); }));
        assertEquals(0, loaded.get());
    }

    @Test void sphericalSchedulerAcceptsCanonicalSeamReceipt() {
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(
            1, at(0, 2), at(7, 2)), 3);
        var loaded = new AtomicInteger();
        assertEquals(1, HydrologySphericalRegionSchedulerV2.loadWithPending(
            List.of(), inbox.snapshot(), 1, 0, 1,
            region -> { loaded.incrementAndGet(); return List.of(); }).size());
        assertEquals(1, loaded.get());
    }

    @Test void pendingDiscoveryRejectsAliasedSourceWithoutConsumingWater()
            throws IOException {
        var worldPath = directory.resolve("world.bin");
        var floodPath = directory.resolve("flood.bin");
        var runner = HydrologyWorldRunnerV2.open(worldPath, 0, "seed");
        var alias = new HydrologyTransferLedgerV2.Key(
            1, at(8, 2), at(7, 2));
        assertTrue(runner.receive(alias, 4));
        var outlet = at(0, 2);
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(outlet), address -> address.equals(outlet) ? 0 : 10);
        session.advance(1);
        var original = session.checkpoint("fixed-terrain");
        HydrologyIncrementalFloodCheckpointStoreV2.save(floodPath, original);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.discoverPendingPersisted(
                floodPath, worldPath, "fixed-terrain", session, 100));
        assertEquals(original,
            HydrologyIncrementalFloodCheckpointStoreV2.load(floodPath));
        assertEquals(4, HydrologyWorldRunnerV2.open(
            worldPath, 0, "ignored").receiptBudget().pendingVolume(), 1e-12);
    }
}
