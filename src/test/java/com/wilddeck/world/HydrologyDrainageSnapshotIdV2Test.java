package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyDrainageSnapshotIdV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet(double water) {
        var result=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),water));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            result.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),water));
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),water));
        return result;
    }
    private static HydrologySphericalRouteAtlasV2 compile(
            List<HydrologyGlobalSlopeRouterV2.Source> sources,
            Set<GlobalHydrologyAddressV2.Sample> outlets) {
        return HydrologySphericalRouteAtlasV2.compile(sources,
            a->a.equals(at(0,2))||a.equals(at(4,2))?0:10,outlets);
    }

    @Test void identityIgnoresRainfallAndSourceOrderingButBindsOutlets() {
        var original=compile(planet(1),Set.of(at(0,2)));
        var reordered=planet(7);
        Collections.shuffle(reordered,new Random(33));
        var same=compile(reordered,Set.of(at(0,2)));
        var changed=compile(planet(1),Set.of(at(0,2),at(4,2)));
        String id=HydrologyDrainageSnapshotIdV2.of(original);
        assertEquals(64,id.length());
        assertEquals(id,HydrologyDrainageSnapshotIdV2.of(same));
        assertNotEquals(id,HydrologyDrainageSnapshotIdV2.of(changed));
        assertEquals(id,HydrologyDrainageSnapshotIdV2.of(original));
    }
}
