package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyIncrementalRegionBridgeV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUTLET = at(0, 2);

    private static HydrologyIncrementalPriorityFloodV2.Session flood() {
        return HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), a -> a.equals(OUTLET) ? 0 : 10);
    }

    private static List<HydrologyGlobalSlopeRouterV2.Source> water(
            HydrologyIncrementalPriorityFloodV2.Snapshot snapshot,
            GlobalHydrologyAddressV2.Sample wet) {
        return snapshot.settled().keySet().stream()
            .map(a -> new HydrologyGlobalSlopeRouterV2.Source(
                a, a.equals(wet) ? 1 : 0)).toList();
    }

    @Test void canonicalOwnerCoversSeamSharedCornersAndPoles() {
        var bridge = HydrologyIncrementalRegionBridgeV2.class;
        assertEquals(new HydrologyRegionSchedulerV2.Region(0, 0),
            HydrologyIncrementalRegionBridgeV2.owner(at(0, 0)));
        assertEquals(new HydrologyRegionSchedulerV2.Region(0, 1),
            HydrologyIncrementalRegionBridgeV2.owner(at(0, 4)));
        assertEquals(new HydrologyRegionSchedulerV2.Region(0, 0),
            HydrologyIncrementalRegionBridgeV2.owner(at(2, 2)));
        assertEquals(new HydrologyRegionSchedulerV2.Region(3, 0),
            HydrologyIncrementalRegionBridgeV2.owner(at(7, 2)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.owner(at(8, 2)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.owner(at(1, 0)));
    }

    @Test void partitionHasEveryVertexOnceAndPreservesCatchmentAndReachIdentity() {
        var session = flood();
        var snapshot = session.advance(100);
        assertTrue(snapshot.complete());
        var regions = HydrologyIncrementalRegionBridgeV2.partition(
            snapshot, water(snapshot, at(7, 2)));
        assertEquals(8, regions.size());
        var joined = HydrologyMosaicJoinV2.merge(regions.values());
        assertEquals(26, joined.size());
        assertEquals(26, joined.stream().map(HydrologyFlowGraphV2.Node::address)
            .distinct().count());
        var result = HydrologyFlowGraphV2.solve(joined);
        assertEquals(1, result.terminalOutflow().get(OUTLET), 1e-12);
        assertEquals(0, HydrologyConservationV2.verify(joined, result).residual(), 1e-12);
        assertEquals(25, HydrologyRiverIdentityV2.resolvedReaches(joined, result).size());
        assertEquals(26, result.resolvedCatchments().size());
        assertTrue(result.resolvedCatchments().values().stream()
            .allMatch(OUTLET::equals));
        var reversed = new ArrayList<>(water(snapshot, at(7, 2)));
        Collections.reverse(reversed);
        assertEquals(regions, HydrologyIncrementalRegionBridgeV2.partition(snapshot, reversed));
        assertThrows(UnsupportedOperationException.class, () -> regions.clear());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.provider(regions).load(
                new HydrologyRegionSchedulerV2.Region(10, 10)));
    }

    @Test void rejectsMissingDuplicateUnsettledAndForgedDrainage() {
        var session = flood();
        var snapshot = session.advance(2);
        var owned = water(snapshot, OUTLET);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.partition(snapshot, List.of()));
        var duplicate = new ArrayList<>(owned);
        duplicate.add(owned.get(0));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.partition(snapshot, duplicate));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.partition(snapshot,
                List.of(new HydrologyGlobalSlopeRouterV2.Source(at(8, 2), 1))));
        var forged = new TreeMap<GlobalHydrologyAddressV2.Sample,
                                 HydrologyIncrementalPriorityFloodV2.Route>(
                                     HydrologyFlowGraphV2.ADDRESS_ORDER);
        forged.putAll(snapshot.settled());
        forged.put(OUTLET, new HydrologyIncrementalPriorityFloodV2.Route(
            0, 0, null, new HydrologyRiverIdentityV2.CatchmentId(at(1, 2))));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.partition(
                new HydrologyIncrementalPriorityFloodV2.Snapshot(forged, false), owned));
    }

    @Test void persistedFrontierMustMatchSessionBeforeRegionPublication()
            throws IOException {
        var session = flood();
        var snapshot = session.advance(1);
        var checkpoint = directory.resolve("flood.bin");
        HydrologyIncrementalFloodCheckpointStoreV2.save(
            checkpoint, session.checkpoint("terrain-fixed"));
        assertEquals(HydrologyIncrementalRegionBridgeV2.partition(
                snapshot, water(snapshot, OUTLET)),
            HydrologyIncrementalRegionBridgeV2.partitionPersisted(
                checkpoint, "terrain-fixed", session, water(snapshot, OUTLET)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.partitionPersisted(
                checkpoint, "other-terrain", session, water(snapshot, OUTLET)));
        session.advance(1);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.partitionPersisted(
                checkpoint, "terrain-fixed", session, water(session.snapshot(), OUTLET)));
    }

    @Test void incrementalRegionReceiptsCrossUnloadedSeamExactlyOnce()
            throws IOException {
        var session = flood();
        var snapshot = session.advance(100);
        var source = at(7, 2);
        assertEquals(OUTLET, snapshot.settled().get(source).downstream());
        var partition = HydrologyIncrementalRegionBridgeV2.partition(
            snapshot, water(snapshot, source));
        var sourceOwner = HydrologyIncrementalRegionBridgeV2.owner(source);
        var outletOwner = HydrologyIncrementalRegionBridgeV2.owner(OUTLET);
        assertNotEquals(sourceOwner, outletOwner);

        var world = directory.resolve("world.bin");
        var runner = HydrologyWorldRunnerV2.open(world, 0, "initial");
        var upstream = runner.advanceAndQueueKnownFrontier(
            "upstream", List.of(partition.get(sourceOwner)));
        assertEquals(1, upstream.committed().flow().pendingOutflow().get(OUTLET), 1e-12);
        assertEquals(1, runner.receiptBudget().pendingVolume(), 1e-12);

        runner = HydrologyWorldRunnerV2.open(world, 0, "ignored");
        var downstream = runner.advanceAndQueueKnownFrontier(
            "downstream", List.of(partition.get(outletOwner)));
        assertEquals(1, downstream.committed().flow().terminalOutflow().get(OUTLET), 1e-12);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);

        runner = HydrologyWorldRunnerV2.open(world, 0, "ignored");
        assertEquals(2, runner.epoch());
        var replay = runner.advanceAndQueueKnownFrontier(
            "dry", List.of(partition.get(outletOwner)));
        assertEquals(0, replay.committed().flow().terminalOutflow().get(OUTLET), 1e-12);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
    }
    @Test void parkedWaterTriggersBoundedDurableDiscoveryThenRoutesOnce()
            throws IOException {
        var worldPath = directory.resolve("pending-world.bin");
        var floodPath = directory.resolve("pending-flood.bin");
        var runner = HydrologyWorldRunnerV2.open(worldPath, 0, "seed");
        var destination = at(4, 2);
        var key = new HydrologyTransferLedgerV2.Key(1, at(3, 2), destination);
        assertTrue(runner.receive(key, 4));

        var session = flood();
        session.advance(1);
        HydrologyIncrementalFloodCheckpointStoreV2.save(
            floodPath, session.checkpoint("terrain-fixed"));
        var partial = HydrologyIncrementalRegionBridgeV2.discoverPendingPersisted(
            floodPath, worldPath, "terrain-fixed", session, 1);
        assertFalse(partial.ready());
        assertEquals(Set.of(destination), partial.unresolved());
        assertEquals(2, HydrologyIncrementalFloodCheckpointStoreV2.load(
            floodPath).settledCount());
        assertEquals(4, runner.receiptBudget().pendingVolume(), 1e-12);

        session = HydrologyIncrementalPriorityFloodV2.resume(
            HydrologyIncrementalFloodCheckpointStoreV2.load(floodPath),
            "terrain-fixed", a -> a.equals(OUTLET) ? 0 : 10);
        var ready = HydrologyIncrementalRegionBridgeV2.discoverPendingPersisted(
            floodPath, worldPath, "terrain-fixed", session, 100);
        assertTrue(ready.ready());
        assertTrue(ready.snapshot().settled().containsKey(destination));
        assertEquals(ready.snapshot().settled().size(),
            HydrologyIncrementalFloodCheckpointStoreV2.load(
                floodPath).settledCount());
        var zeros = ready.snapshot().settled().keySet().stream()
            .map(a -> new HydrologyGlobalSlopeRouterV2.Source(a, 0)).toList();
        var regions = HydrologyIncrementalRegionBridgeV2.partitionPersisted(
            floodPath, "terrain-fixed", session, zeros);

        var routed = runner.advanceAndQueueKnownFrontier(
            "delivered", regions.values());
        assertEquals(4, routed.committed().flow().terminalOutflow()
            .get(OUTLET), 1e-12);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
        runner = HydrologyWorldRunnerV2.open(worldPath, 0, "ignored");
        assertEquals(Set.of(key), runner.inboxSnapshot().delivered());
        assertEquals(0, runner.advanceAndQueueKnownFrontier(
            "no-replay", regions.values()).committed().flow()
            .terminalOutflow().get(OUTLET), 1e-12);
    }

    @Test void pendingDiscoveryRejectsStaleFloodAndMismatchedReceiptWithoutMutation()
            throws IOException {
        var worldPath = directory.resolve("reject-world.bin");
        var floodPath = directory.resolve("reject-flood.bin");
        var runner = HydrologyWorldRunnerV2.open(worldPath, 0, "seed");
        var wrongLevel = new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(0, 2, 1, 1),
            new GlobalHydrologyAddressV2.Sample(0, 2, 0, 1));
        runner.receive(wrongLevel, 7);
        var session = flood();
        session.advance(1);
        var original = session.checkpoint("terrain-fixed");
        HydrologyIncrementalFloodCheckpointStoreV2.save(floodPath, original);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.discoverPendingPersisted(
                floodPath, worldPath, "terrain-fixed", session, 100));
        assertEquals(original,
            HydrologyIncrementalFloodCheckpointStoreV2.load(floodPath));
        assertEquals(7, runner.receiptBudget().pendingVolume(), 1e-12);

        session.advance(1);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalRegionBridgeV2.discoverPendingPersisted(
                floodPath, worldPath, "terrain-fixed", session, 100));
        assertEquals(original,
            HydrologyIncrementalFloodCheckpointStoreV2.load(floodPath));
    }

}
