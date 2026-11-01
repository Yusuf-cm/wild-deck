package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalLakeBasinsV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUTLET=at(0,2);
    private static final GlobalHydrologyAddressV2.Sample PIT=at(4,2);
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return sources;
    }

    @Test void closedSphereIdentifiesStableDepressionAndConditionalOverflow() {
        double radius=1000;
        var sources=planet();
        HydrologyGlobalSlopeRouterV2.Elevations terrain=
            s->s.equals(OUTLET)?0:s.equals(PIT)?-5:10;
        var plan=HydrologySphericalLakeBasinsV2.extract(
            sources,terrain,Set.of(OUTLET),radius);
        assertEquals(26,plan.routes().size());
        assertEquals(1,plan.lakes().size());
        var lake=plan.lakes().get(0);
        assertEquals(PIT,lake.anchor());
        assertEquals(List.of(PIT),lake.cells());
        assertEquals(OUTLET,lake.confirmedOutlet());
        assertTrue(HydrologySphericalTopologyV2.adjacent(
            PIT,lake.spillTarget()));
        assertEquals(10,lake.reservoir().spillElevation(),0);
        double capacity=15*HydrologySphericalCellAreaV2.areaAt(PIT,radius);
        assertEquals(capacity,lake.reservoir().capacity(),capacity*1e-12);
        var dry=LakeReservoirV2.step(lake.reservoir(),0,capacity/2,0);
        assertEquals(0,dry.overflow(),0);
        assertTrue(dry.stage()<10);
        var full=LakeReservoirV2.step(lake.reservoir(),
            dry.volume(),capacity/2+7,0);
        assertEquals(capacity,full.volume(),capacity*1e-12);
        assertEquals(7,full.overflow(),1e-8);
        assertEquals(10,full.stage(),1e-12);
        Collections.reverse(sources);
        var reversed=HydrologySphericalLakeBasinsV2.extract(
            sources,terrain,Set.of(OUTLET),radius);
        assertEquals(plan.routes(),reversed.routes());
        assertEquals(lake.anchor(),reversed.lakes().get(0).anchor());
        assertEquals(lake.cells(),reversed.lakes().get(0).cells());
        assertEquals(lake.spillTarget(),reversed.lakes().get(0).spillTarget());
    }

    @Test void closedSphereWithNoDepressionHasNoReservoir() {
        var plan=HydrologySphericalLakeBasinsV2.extract(
            planet(),s->10,Set.of(at(0,0)),1000);
        assertTrue(plan.lakes().isEmpty());
    }

    @Test void partialPlanetAndUnverifiedOutletsFailClosed() {
        var partial=planet();
        partial.removeIf(s->s.address().equals(at(7,2)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalLakeBasinsV2.extract(
                partial,s->10,Set.of(OUTLET),1000));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalLakeBasinsV2.extract(
                planet(),s->10,Set.of(),1000));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalLakeBasinsV2.extract(
                planet(),s->10,Set.of(OUTLET),-1));
    }
}
