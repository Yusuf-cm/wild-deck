package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalOceanOutletsV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var result=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),1));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            result.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),1));
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),1));
        return result;
    }
    private static final GlobalHydrologyAddressV2.Sample SEAM=at(7,2);
    private static final GlobalHydrologyAddressV2.Sample OCEAN=at(0,2);
    private static final GlobalHydrologyAddressV2.Sample SECOND=at(2,2);
    private static final GlobalHydrologyAddressV2.Sample PIT=at(4,2);
    private static HydrologyGlobalSlopeRouterV2.Elevations terrain() {
        return a->a.equals(OCEAN)||a.equals(SEAM)||a.equals(SECOND)?-2:
            a.equals(PIT)?-5:10;
    }

    @Test void seededOceanConnectivityIncludesSeamButNotIsolatedInlandDepression() {
        var sources=planet();
        var mask=HydrologySphericalOceanOutletsV2.classify(
            sources,terrain(),0,Set.of(SEAM,SECOND));
        assertEquals(Set.of(OCEAN,SEAM,SECOND),mask.ocean());
        assertEquals(Set.of(PIT),mask.inland());
        assertEquals(OCEAN,mask.oceanComponents().get(SEAM));
        assertEquals(OCEAN,mask.oceanComponents().get(OCEAN));
        assertEquals(SECOND,mask.oceanComponents().get(SECOND));
        var routes=HydrologySphericalOceanOutletsV2.route(
            sources,terrain(),0,Set.of(SECOND,SEAM));
        var solved=HydrologyFlowGraphV2.solve(routes);
        assertEquals(26,routes.size());
        assertEquals(26,solved.resolvedCatchments().size());
        assertEquals(26,solved.terminalOutflow().values().stream()
            .mapToDouble(Double::doubleValue).sum(),1e-9);
        assertTrue(solved.pendingOutflow().isEmpty());
        assertEquals(0,HydrologyConservationV2.verify(routes,solved).residual(),1e-9);
        assertFalse(routes.stream().filter(n->n.address().equals(PIT))
            .findFirst().orElseThrow().terminal());
        Collections.reverse(sources);
        assertEquals(routes,HydrologySphericalOceanOutletsV2.route(
            sources,terrain(),0,Set.of(SEAM,SECOND)));
    }

    @Test void oceanOutletsCanDriveConditionalInlandLakeStorage() {
        var plan=HydrologySphericalOceanOutletsV2.lakes(
            planet(),terrain(),0,Set.of(SEAM,SECOND),1000);
        assertTrue(plan.lakes().stream().anyMatch(l->l.cells().contains(PIT)));
        var lake=plan.lakes().stream().filter(l->l.cells().contains(PIT))
            .findFirst().orElseThrow();
        assertEquals(PIT,lake.anchor());
        assertTrue(lake.reservoir().capacity()>0);
        var dry=LakeReservoirV2.step(lake.reservoir(),0,
            lake.reservoir().capacity()/2,0);
        assertEquals(0,dry.overflow(),0);
        assertTrue(dry.stage()<lake.reservoir().spillElevation());
    }

    @Test void polarOceanComponentUsesOneCanonicalPoleAndGlobalRing() {
        var pole=at(0,0);
        var ring=at(7,1);
        var mask=HydrologySphericalOceanOutletsV2.classify(
            planet(),a->a.equals(pole)||a.equals(ring)?-2:10,
            0,Set.of(pole));
        assertEquals(Set.of(pole,ring),mask.ocean());
        assertEquals(pole,mask.oceanComponents().get(ring));
        assertTrue(mask.inland().isEmpty());
    }

    @Test void missingOrUnverifiedOceanFailsClosed() {
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalOceanOutletsV2.classify(
                planet(),terrain(),0,Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalOceanOutletsV2.classify(
                planet(),terrain(),0,Set.of(at(1,2))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalOceanOutletsV2.classify(
                planet(),terrain(),0,Set.of(at(8,2))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalOceanOutletsV2.classify(
                planet(),terrain(),Double.NaN,Set.of(SEAM)));
        var partial=planet();
        partial.removeIf(s->s.address().equals(at(6,3)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalOceanOutletsV2.classify(
                partial,terrain(),0,Set.of(SEAM)));
        var duplicate=planet();
        duplicate.add(new HydrologyGlobalSlopeRouterV2.Source(OCEAN,1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalOceanOutletsV2.classify(
                duplicate,terrain(),0,Set.of(SEAM)));
    }
}
