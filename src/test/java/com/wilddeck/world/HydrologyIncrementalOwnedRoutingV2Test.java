package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyIncrementalOwnedRoutingV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }

    @Test void ownedCrossRegionWaterConservesOnceAcrossSeamAndPoles() {
        var outlet = at(0, 2);
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(outlet), a -> a.equals(outlet) ? 0 : 10);
        var finished = session.advance(100);
        assertTrue(finished.complete());
        var sources = new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        for (var address : finished.settled().keySet())
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(address, 1));
        var nodes = finished.ownedRoutes(sources);
        var solved = HydrologyFlowGraphV2.solve(nodes);
        assertEquals(26, nodes.size());
        assertEquals(26, solved.terminalOutflow().get(outlet), 1e-10);
        assertEquals(0, HydrologyConservationV2.verify(nodes, solved).residual(), 1e-10);
        Collections.reverse(sources);
        assertEquals(nodes, finished.ownedRoutes(sources));
        assertEquals(finished.knownRoutes().size(),
            finished.ownedRoutes(List.of()).size());
    }

    @Test void rejectsSharedVertexDuplicatesAliasesAndUnsettledRainfall() {
        var outlet = at(0, 2);
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(outlet), a -> 10);
        var partial = session.advance(1);
        var owned = new HydrologyGlobalSlopeRouterV2.Source(outlet, 2);
        assertThrows(IllegalArgumentException.class, () ->
            partial.ownedRoutes(List.of(owned, owned)));
        assertThrows(IllegalArgumentException.class, () ->
            partial.ownedRoutes(List.of(
                new HydrologyGlobalSlopeRouterV2.Source(at(8, 2), 2))));
        assertThrows(IllegalArgumentException.class, () ->
            partial.ownedRoutes(List.of(
                new HydrologyGlobalSlopeRouterV2.Source(at(1, 2), 2))));
        var nodes = partial.ownedRoutes(List.of(owned));
        assertEquals(1, nodes.size());
        assertEquals(2, HydrologyFlowGraphV2.solve(nodes)
            .terminalOutflow().get(outlet), 1e-10);
        assertFalse(partial.complete());
    }
}
