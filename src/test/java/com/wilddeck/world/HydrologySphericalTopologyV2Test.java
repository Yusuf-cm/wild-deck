package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalTopologyV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static HydrologyGlobalSlopeRouterV2.Source src(long x,long y,double rain) {
        return new HydrologyGlobalSlopeRouterV2.Source(at(x,y),rain);
    }

    @Test void canonicalizesDatelineAndBothPoles() {
        assertEquals(at(0,5),HydrologySphericalTopologyV2.canonical(at(32,5)));
        assertEquals(at(0,0),HydrologySphericalTopologyV2.canonical(at(18,0)));
        assertEquals(at(0,16),HydrologySphericalTopologyV2.canonical(at(31,16)));
        assertTrue(HydrologySphericalTopologyV2.neighbors(at(0,5)).contains(at(31,5)));
        assertTrue(HydrologySphericalTopologyV2.neighbors(at(31,5)).contains(at(0,5)));
        assertEquals(32,HydrologySphericalTopologyV2.neighbors(at(0,0)).size());
        assertTrue(HydrologySphericalTopologyV2.neighbors(at(0,0)).contains(at(27,1)));
        assertTrue(HydrologySphericalTopologyV2.adjacent(at(27,1),at(0,0)));
        assertFalse(HydrologySphericalTopologyV2.adjacent(at(27,2),at(0,0)));
        assertTrue(HydrologySphericalTopologyV2.angularDistance(at(0,5),at(31,5))>0);
        assertEquals(HydrologySphericalTopologyV2.angularDistance(at(0,5),at(31,5)),
                     HydrologySphericalTopologyV2.angularDistance(at(31,5),at(0,5)),1e-14);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalTopologyV2.neighbors(at(32,5)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalTopologyV2.angularDistance(at(0,0),at(17,0)));
    }

    @Test void seamConfluenceRetainsReachAndConfirmedCatchmentAcrossUnloadedTile() {
        var west=List.of(src(31,5,3));
        var east=List.of(src(0,5,4),src(1,5,5));
        HydrologyGlobalSlopeRouterV2.Elevations elevations=p->{
            if(p.equals(at(31,5)))return 30;
            if(p.equals(at(0,5)))return 20;
            if(p.equals(at(1,5)))return 10;
            return 1000;
        };
        var left=HydrologySphericalTopologyV2.route(west,elevations,Set.of());
        var right=HydrologySphericalTopologyV2.route(east,elevations,Set.of(at(1,5)));
        assertEquals(at(0,5),left.get(0).downstream());
        assertEquals(at(1,5),right.get(0).downstream());
        var combined=new ArrayList<HydrologyFlowGraphV2.Node>(left);
        combined.addAll(right);
        combined.sort(Comparator.comparing(HydrologyFlowGraphV2.Node::address,
            HydrologyFlowGraphV2.ADDRESS_ORDER));
        var solved=HydrologyFlowGraphV2.solve(combined);
        assertEquals(12,solved.terminalOutflow().get(at(1,5)),1e-10);
        assertEquals(3,HydrologyFlowGraphV2.solve(left).pendingOutflow().get(at(0,5)),1e-10);
        assertEquals(new HydrologyRiverIdentityV2.ReachId(at(31,5),at(0,5)),
            HydrologyRiverIdentityV2.knownReaches(combined).stream()
                .filter(r->r.from().equals(at(31,5))).findFirst().orElseThrow());
        assertEquals(3,HydrologyRiverIdentityV2.catchments(solved).size());
        assertEquals(2,HydrologyRiverIdentityV2.resolvedReaches(combined,solved).size());
        HydrologyConservationV2.verify(combined,solved);
        var all=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>(west);
        all.addAll(east);
        assertEquals(combined,HydrologySphericalTopologyV2.route(all,elevations,Set.of(at(1,5))));
        Collections.reverse(all);
        assertEquals(combined,HydrologySphericalTopologyV2.route(all,elevations,Set.of(at(1,5))));
    }

    @Test void polarRoutingChoosesLowestRingSiteAndNeverDuplicatesPole() {
        var north=at(0,0);
        var target=at(27,1);
        HydrologyGlobalSlopeRouterV2.Elevations elevations=p->
            p.equals(north)?100:p.equals(target)?0:200;
        var nodes=HydrologySphericalTopologyV2.route(
            List.of(src(0,0,2),src(27,1,3)),elevations,Set.of(target));
        assertEquals(target,nodes.get(0).downstream());
        assertEquals(5,HydrologyFlowGraphV2.solve(nodes)
            .terminalOutflow().get(target),1e-10);
        HydrologyConservationV2.verify(nodes,HydrologyFlowGraphV2.solve(nodes));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalTopologyV2.route(
                List.of(src(0,0,1),src(5,0,1)),elevations,Set.of()));
        var hugePole=new GlobalHydrologyAddressV2.Sample(12,4,0,0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalTopologyV2.neighbors(hugePole));
    }

    @Test void rejectsInvalidRoutesAndUnverifiedOutlets() {
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalTopologyV2.route(
                List.of(src(31,5,1)),p->1,Set.of(at(0,5))));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyFlowGraphV2.Node(at(31,5),1,at(12,5),false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalTopologyV2.route(
                List.of(src(31,5,1),src(31,5,1)),p->1,Set.of()));
    }
}
