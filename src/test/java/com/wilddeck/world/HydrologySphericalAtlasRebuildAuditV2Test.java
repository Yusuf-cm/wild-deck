package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalAtlasRebuildAuditV2Test {
    @Test void unchangedTerrainPreservesEveryRouteAndCatchment() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        var first=new GlobalHydrologyAddressV2.Sample(0,2,0,0);
        for(long y=0;y<=2;y++)for(long x=0;x<4;x++) {
            var address=new GlobalHydrologyAddressV2.Sample(0,2,x,y);
            if(HydrologySphericalTopologyV2.isCanonical(address))
                sources.add(new HydrologyGlobalSlopeRouterV2.Source(address,1));
        }
        var outlets=Set.of(first);
        HydrologyGlobalSlopeRouterV2.Elevations heights=a->a.equals(first)?0:10;
        var original=HydrologySphericalRouteAtlasV2.compile(sources,heights,outlets);
        var audit=HydrologySphericalAtlasRebuildAuditV2.rebuild(original,sources,heights,outlets);
        assertTrue(audit.changedRoutes().isEmpty());
        assertTrue(audit.changedCatchments().isEmpty());
        assertEquals(original.catchments(),audit.rebuilt().catchments());
    }
}
