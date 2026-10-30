package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalRouteAtlasV2Test {
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

    @Test void eightIndependentRegionSlicesRetainGlobalCatchmentsAndUniqueSources() {
        var outlet=at(0,2);
        var atlas=HydrologySphericalRouteAtlasV2.compile(
            planet(),a->a.equals(outlet)?0:a.equals(at(4,2))?-5:10,
            Set.of(outlet));
        assertEquals(26,atlas.routes().size());
        assertEquals(26,atlas.catchments().size());
        assertEquals(25,atlas.reaches().size());
        assertTrue(atlas.catchments().values().stream()
            .allMatch(id->id.outlet().equals(outlet)));
        var regions=new ArrayList<List<HydrologyFlowGraphV2.Node>>();
        for(long row=0;row<2;row++)for(long column=0;column<4;column++)
            regions.add(atlas.ownedRegion(
                new HydrologyRegionSchedulerV2.Region(column,row)));
        assertEquals(26,regions.stream().mapToInt(List::size).sum());
        atlas.verifyPartition(regions);
        var seam=at(7,2);
        assertTrue(regions.stream().anyMatch(r->r.contains(atlas.routeAt(seam))));
        assertEquals(atlas.routeAt(at(0,0)),regions.get(0).stream()
            .filter(n->n.address().equals(at(0,0))).findFirst().orElseThrow());
        Collections.reverse(regions);
        atlas.verifyPartition(regions);
        var shuffled=planet();
        Collections.shuffle(shuffled,new Random(42));
        var replay=HydrologySphericalRouteAtlasV2.compile(
            shuffled,a->a.equals(outlet)?0:a.equals(at(4,2))?-5:10,
            Set.of(outlet));
        assertEquals(atlas.routes(),replay.routes());
        assertEquals(atlas.catchments(),replay.catchments());
        assertEquals(atlas.reaches(),replay.reaches());
    }

    @Test void oceanMaskBuildsStableOutletIdentitiesWithoutLoadingIndividualTiles() {
        var sea=at(0,2);
        var seam=at(7,2);
        var pit=at(4,2);
        var atlas=HydrologySphericalRouteAtlasV2.compileWithOcean(
            planet(),a->a.equals(sea)||a.equals(seam)?-2:
                a.equals(pit)?-5:10,0,Set.of(seam));
        assertEquals(26,atlas.catchments().size());
        assertEquals(24,atlas.reaches().size());
        assertTrue(atlas.routeAt(seam).terminal());
        assertTrue(atlas.routeAt(sea).terminal());
        assertFalse(atlas.routeAt(pit).terminal());
        assertTrue(atlas.catchments().containsKey(pit));
    }

    @Test void partitionRejectsDuplicatesMissingAndModifiedRoutes() {
        var outlet=at(0,2);
        var atlas=HydrologySphericalRouteAtlasV2.compile(
            planet(),a->a.equals(outlet)?0:10,Set.of(outlet));
        var regions=new ArrayList<List<HydrologyFlowGraphV2.Node>>();
        for(long row=0;row<2;row++)for(long column=0;column<4;column++)
            regions.add(atlas.ownedRegion(
                new HydrologyRegionSchedulerV2.Region(column,row)));
        var missing=new ArrayList<>(regions);
        missing.remove(0);
        assertThrows(IllegalArgumentException.class,()->atlas.verifyPartition(missing));
        var duplicate=new ArrayList<>(regions);
        duplicate.add(regions.get(0));
        assertThrows(IllegalArgumentException.class,()->atlas.verifyPartition(duplicate));
        var modified=new ArrayList<>(regions);
        var first=new ArrayList<>(modified.get(0));
        var node=first.get(0);
        first.set(0,new HydrologyFlowGraphV2.Node(
            node.address(),node.localWater()+1,node.downstream(),node.terminal()));
        modified.set(0,first);
        assertThrows(IllegalArgumentException.class,()->atlas.verifyPartition(modified));
        assertThrows(IllegalArgumentException.class,()->
            atlas.ownedRegion(new HydrologyRegionSchedulerV2.Region(4,0)));
        assertThrows(IllegalArgumentException.class,()->atlas.routeAt(at(8,2)));
        var partial=planet();
        partial.remove(0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalRouteAtlasV2.compile(
                partial,a->10,Set.of(outlet)));
    }
}
