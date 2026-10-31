package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyIncrementalPriorityFloodV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUTLET = at(0, 2);
    private static final GlobalHydrologyAddressV2.Sample PIT = at(4, 2);

    private static HydrologyGlobalSlopeRouterV2.Elevations heights() {
        return a -> a.equals(OUTLET) ? 0 : a.equals(PIT) ? -5 : 10;
    }

    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var sources = new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0, 0), 1));
        for (long y = 1; y < 4; y++)
            for (long x = 0; x < 8; x++)
                sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x, y), 1));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0, 4), 1));
        return sources;
    }

    @Test void incrementalFloodMatchesClosedSphereAndPreservesVerifiedCatchments() {
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), heights());
        var first = session.advance(1);
        assertFalse(first.complete());
        assertEquals(Set.of(OUTLET), first.settled().keySet());
        assertEquals(List.of(new HydrologyFlowGraphV2.Node(OUTLET, 0, null, true)),
            first.knownRoutes());
        var second = session.advance(3);
        assertEquals(4, second.settled().size());
        assertFalse(second.complete());
        assertTrue(second.settled().keySet().containsAll(first.settled().keySet()));
        assertEquals(first.settled().get(OUTLET), second.settled().get(OUTLET));

        HydrologyIncrementalPriorityFloodV2.Snapshot finished = second;
        for (int i = 0; i < 26 && !finished.complete(); i++)
            finished = session.advance(2);
        assertTrue(finished.complete());
        assertEquals(26, finished.settled().size());
        assertEquals(10, finished.settled().get(PIT).spillElevation());
        assertEquals(new HydrologyRiverIdentityV2.CatchmentId(OUTLET),
            finished.settled().get(PIT).catchment());

        var expected = HydrologySphericalBasinRouterV2.route(
            planet(), heights(), Set.of(OUTLET));
        var actual = finished.knownRoutes();
        assertEquals(expected.stream().map(n -> new HydrologyFlowGraphV2.Node(
            n.address(), 0, n.downstream(), n.terminal())).toList(), actual);
        var withWater = actual.stream().map(n -> new HydrologyFlowGraphV2.Node(
            n.address(), 1, n.downstream(), n.terminal())).toList();
        var solved = HydrologyFlowGraphV2.solve(withWater);
        assertEquals(26, solved.terminalOutflow().get(OUTLET), 1e-10);
        assertTrue(solved.pendingOutflow().isEmpty());
        assertEquals(26, solved.resolvedCatchments().size());
        assertEquals(0, HydrologyConservationV2.verify(withWater, solved).residual(), 1e-10);
    }

    @Test void batchSizeAndOutletOrderDoNotChangeRoutesAcrossSeamOrPoles() {
        var north = at(0, 0);
        var south = at(0, 4);
        var one = HydrologyIncrementalPriorityFloodV2.start(
            new LinkedHashSet<>(List.of(north, south)), a -> 10);
        var two = HydrologyIncrementalPriorityFloodV2.start(
            new LinkedHashSet<>(List.of(south, north)), a -> 10);
        var full = one.advance(100);
        HydrologyIncrementalPriorityFloodV2.Snapshot batches;
        do { batches = two.advance(1); } while (!batches.complete());
        assertEquals(full, batches);
        assertEquals(26, full.settled().size());
        assertEquals(HydrologySphericalBasinRouterV2.route(
            planet(), a -> 10, Set.of(north, south)).stream()
                .map(n -> new HydrologyFlowGraphV2.Node(
                    n.address(), 0, n.downstream(), n.terminal())).toList(),
            full.knownRoutes());
        assertEquals(full, one.snapshot());
        assertEquals(full, one.advance(1));
    }

    @Test void partialSnapshotContainsOnlySettledRoutesAndNeverClaimsCompletion() {
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), heights());
        var partial = session.advance(2);
        assertFalse(partial.complete());
        assertEquals(2, partial.knownRoutes().size());
        for (var node : partial.knownRoutes())
            if (node.downstream() != null)
                assertTrue(partial.settled().containsKey(node.downstream()));
        assertEquals(2, HydrologyFlowGraphV2.solve(partial.knownRoutes())
            .resolvedCatchments().size());
        assertThrows(UnsupportedOperationException.class, () ->
            partial.settled().clear());
    }

    @Test void rejectsUnknownOutletsInvalidBudgetsAndNonfiniteElevations() {
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.start(Set.of(), heights()));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.start(Set.of(at(8, 2)), heights()));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.start(Set.of(OUTLET), a -> Double.NaN));
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), a -> a.equals(OUTLET) ? 0 : Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> session.advance(1));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.start(
                Set.of(OUTLET), heights()).advance(0));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.start(
                Set.of(OUTLET), heights()).advance(
                    HydrologyIncrementalPriorityFloodV2.MAX_SAMPLES + 1));
    }
    @Test void regionPartitionPreservesUniqueRainfallAndResolvedReachIdentity() {
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), heights());
        var complete = session.advance(100);
        assertTrue(complete.complete());
        var regions = new LinkedHashMap<HydrologyRegionSchedulerV2.Region,
            List<HydrologyFlowGraphV2.Node>>();
        for (var route : complete.knownRoutes()) {
            var a = route.address();
            long column = a.y() == 0 || a.y() == 4 ? 0 :
                a.x() == 0 ? 0 : (a.x() - 1) / 2;
            long row = a.y() == 0 ? 0 : (a.y() - 1) / 2;
            var owner = new HydrologyRegionSchedulerV2.Region(column, row);
            regions.computeIfAbsent(owner, ignored -> new ArrayList<>())
                .add(new HydrologyFlowGraphV2.Node(
                    a, 1, route.downstream(), route.terminal()));
        }
        assertEquals(8, regions.size());
        var joined = HydrologyMosaicJoinV2.merge(regions.values());
        assertEquals(26, joined.size());
        assertEquals(26, joined.stream().map(HydrologyFlowGraphV2.Node::address)
            .distinct().count());
        var solved = HydrologyFlowGraphV2.solve(joined);
        assertEquals(26, solved.terminalOutflow().get(OUTLET), 1e-10);
        assertTrue(solved.pendingOutflow().isEmpty());
        assertEquals(25, HydrologyRiverIdentityV2.resolvedReaches(
            joined, solved).size());
        assertEquals(0, HydrologyConservationV2.verify(
            joined, solved).residual(), 1e-10);
        var reverse = new ArrayList<Collection<HydrologyFlowGraphV2.Node>>(
            regions.values());
        Collections.reverse(reverse);
        assertEquals(joined, HydrologyMosaicJoinV2.merge(reverse));
    }


    @Test void ownedRoutesRequiresEverySettledWaterOwnerExactlyOnce() {
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), heights());
        var snapshot = session.advance(2);
        var other = snapshot.settled().keySet().stream()
            .filter(a -> !a.equals(OUTLET)).findFirst().orElseThrow();
        var sources = List.of(
            new HydrologyGlobalSlopeRouterV2.Source(OUTLET, 3),
            new HydrologyGlobalSlopeRouterV2.Source(other, 0));
        var nodes = snapshot.ownedRoutes(sources);
        assertEquals(2, nodes.size());
        assertEquals(3, HydrologyFlowGraphV2.solve(nodes)
            .terminalOutflow().get(OUTLET), 1e-12);
        var reversed = new ArrayList<>(sources);
        Collections.reverse(reversed);
        assertEquals(nodes, snapshot.ownedRoutes(reversed));

        assertThrows(IllegalArgumentException.class, () ->
            snapshot.ownedRoutes(List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            snapshot.ownedRoutes(List.of(sources.get(0))));
        assertThrows(IllegalArgumentException.class, () ->
            snapshot.ownedRoutes(List.of(sources.get(0), sources.get(0))));
        assertThrows(IllegalArgumentException.class, () ->
            snapshot.ownedRoutes(List.of(
                sources.get(0),
                new HydrologyGlobalSlopeRouterV2.Source(at(8, 2), 1))));
        assertEquals(0, HydrologyFlowGraphV2.solve(snapshot.knownRoutes())
            .terminalOutflow().get(OUTLET), 1e-12);
    }

}
