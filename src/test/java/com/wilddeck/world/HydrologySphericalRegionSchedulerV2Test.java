package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalRegionSchedulerV2Test {
    private static HydrologyRegionSchedulerV2.Region region(long x,long y) {
        return new HydrologyRegionSchedulerV2.Region(x,y);
    }
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    @Test void datelineWrapsAndPoleClampsWithoutDuplicateTileLoads() {
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var loaded=HydrologySphericalRegionSchedulerV2.load(
            List.of(region(0,0)),1,1,6,r->{
                visited.add(r);
                return List.of();
            });
        assertEquals(6,loaded.size());
        assertEquals(List.of(region(0,0),region(1,0),region(3,0),
                             region(0,1),region(1,1),region(3,1)),visited);
        visited.clear();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalRegionSchedulerV2.load(
                List.of(region(0,0)),1,1,5,r->{
                    visited.add(r);
                    return List.of();
                }));
        assertTrue(visited.isEmpty());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalRegionSchedulerV2.load(
                List.of(region(-1,0)),1,0,1,r->List.of()));
    }

    @Test void parkedSeamWaterAddsOwningRegionAndDeduplicatesFocus() {
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,at(7,3),at(0,3)),3);
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        HydrologySphericalRegionSchedulerV2.loadWithPending(
            List.of(region(3,1),region(3,1)),inbox.snapshot(),
            1,0,2,r->{
                visited.add(r);
                return List.of();
            });
        assertEquals(List.of(region(0,1),region(3,1)),visited);
    }

    @Test void realSeededProviderConservesWaterAcrossWrappedHalo() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(terrain);
        var provider=new HydrologySphericalSlopeRegionProviderV2(
            terrain,climate,1,2,2,Set.of());
        var loaded=HydrologySphericalRegionSchedulerV2.load(
            List.of(region(0,1)),1,1,6,provider);
        var joined=HydrologyMosaicJoinV2.merge(loaded);
        var addresses=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:joined)assertTrue(addresses.add(node.address()));
        HydrologyConservationV2.verify(joined,HydrologyFlowGraphV2.solve(joined));
        assertEquals(loaded,HydrologySphericalRegionSchedulerV2.load(
            List.of(region(0,1)),1,1,6,provider));
    }
}
