package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalBasinRouterV2Test {
    private static final int LEVEL=1,DIVISIONS=2;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(LEVEL,DIVISIONS,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var result=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),1));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            result.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),1));
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),1));
        return result;
    }
    private static final GlobalHydrologyAddressV2.Sample OUTLET=at(0,2);
    private static final GlobalHydrologyAddressV2.Sample PIT=at(4,2);
    private static HydrologyGlobalSlopeRouterV2.Elevations heights() {
        return p->p.equals(OUTLET)?0:p.equals(PIT)?-5:10;
    }

    @Test void closedSphereFillsPitsAndFlatsToVerifiedOutletWithoutLosingWater() {
        var sources=planet();
        var routed=HydrologySphericalBasinRouterV2.route(
            sources,heights(),Set.of(OUTLET));
        assertEquals(26,routed.size());
        var pit=routed.stream().filter(n->n.address().equals(PIT))
            .findFirst().orElseThrow();
        assertNotNull(pit.downstream());
        assertTrue(HydrologySphericalTopologyV2.adjacent(PIT,pit.downstream()));
        var solved=HydrologyFlowGraphV2.solve(routed);
        assertEquals(26,solved.terminalOutflow().get(OUTLET),1e-10);
        assertTrue(solved.pendingOutflow().isEmpty());
        assertEquals(26,HydrologyRiverIdentityV2.catchments(solved).size());
        assertEquals(25,HydrologyRiverIdentityV2.resolvedReaches(routed,solved).size());
        assertEquals(0,HydrologyConservationV2.verify(routed,solved).residual(),1e-10);
        Collections.reverse(sources);
        assertEquals(routed,HydrologySphericalBasinRouterV2.route(
            sources,heights(),Set.of(OUTLET)));
    }

    @Test void multipleVerifiedOutletsRemainDeterministicAcrossSeamAndPoles() {
        var north=at(0,0);
        var south=at(0,4);
        var sources=planet();
        var routed=HydrologySphericalBasinRouterV2.route(
            sources,p->10,Set.of(north,south));
        var solved=HydrologyFlowGraphV2.solve(routed);
        assertEquals(2,solved.terminalOutflow().size());
        assertEquals(26,solved.terminalOutflow().values().stream()
            .mapToDouble(Double::doubleValue).sum(),1e-10);
        assertTrue(solved.pendingOutflow().isEmpty());
        assertEquals(26,solved.resolvedCatchments().size());
        Collections.shuffle(sources,new Random(17));
        assertEquals(routed,HydrologySphericalBasinRouterV2.route(
            sources,p->10,Set.of(south,north)));
        HydrologyConservationV2.verify(routed,solved);
    }

    @Test void refusesUnknownNeighborRatherThanInventingBoundaryOcean() {
        var partial=planet();
        partial.removeIf(s->s.address().equals(at(7,2)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalBasinRouterV2.route(
                partial,heights(),Set.of(OUTLET)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalBasinRouterV2.route(
                planet(),heights(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalBasinRouterV2.route(
                planet(),heights(),Set.of(at(8,2))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalBasinRouterV2.route(
                planet(),p->Double.NaN,Set.of(OUTLET)));
        var duplicated=planet();
        duplicated.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalBasinRouterV2.route(
                duplicated,heights(),Set.of(OUTLET)));
    }

    @Test void partitionedRegionsRetainOneGlobalRouteAndNoSharedVertexRainfall() {
        var routed=HydrologySphericalBasinRouterV2.route(
            planet(),heights(),Set.of(OUTLET));
        var regions=new LinkedHashMap<HydrologyRegionSchedulerV2.Region,
                                      List<HydrologyFlowGraphV2.Node>>();
        for(var node:routed) {
            var address=node.address();
            long col=address.y()==0||address.y()==4?0:
                address.x()==0?0:(address.x()-1)/DIVISIONS;
            long row=address.y()==0?0:(address.y()-1)/DIVISIONS;
            var region=new HydrologyRegionSchedulerV2.Region(col,row);
            regions.computeIfAbsent(region,k->new ArrayList<>()).add(node);
        }
        assertEquals(8,regions.size());
        var joined=HydrologyMosaicJoinV2.merge(regions.values());
        assertEquals(routed,joined);
        assertEquals(26,joined.stream().map(HydrologyFlowGraphV2.Node::address)
            .distinct().count());
        var global=HydrologyFlowGraphV2.solve(joined);
        assertEquals(26,global.terminalOutflow().get(OUTLET),1e-10);
        assertTrue(global.pendingOutflow().isEmpty());
        var reversed=new ArrayList<Collection<HydrologyFlowGraphV2.Node>>(
            regions.values());
        Collections.reverse(reversed);
        assertEquals(joined,HydrologyMosaicJoinV2.merge(reversed));
        assertEquals(global,HydrologyFlowGraphV2.solve(
            HydrologyMosaicJoinV2.merge(reversed)));
    }

}
