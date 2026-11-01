package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalSlopeRegionProviderV2Test {
    private static final int LEVEL=1,DIVISIONS=2;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(LEVEL,DIVISIONS,x,y);
    }

    @Test void entireSmallPlanetHasUniqueSeamAndPolarRainfallOwnership() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),987654321L);
        var climate=new PlanetClimateV2(terrain);
        var provider=new HydrologySphericalSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,3,Set.of());
        var regions=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        for(int row=0;row<2;row++)for(int column=0;column<4;column++)
            regions.add(new HydrologyRegionSchedulerV2.Region(column,row));
        var loaded=HydrologyRegionSchedulerV2.load(regions,0,8,provider);
        var joined=HydrologyMosaicJoinV2.merge(loaded);
        assertEquals(26,joined.size()); // 8 longitude sites x 3 rings + 2 poles
        assertEquals(1,joined.stream().filter(n->n.address().equals(at(0,0))).count());
        assertEquals(1,joined.stream().filter(n->n.address().equals(at(0,4))).count());
        assertTrue(joined.stream().noneMatch(n->n.address().x()==8));
        assertEquals(List.copyOf(provider.load(regions.get(0))),
                     List.copyOf(provider.load(regions.get(0))));
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        for(var node:joined)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(
                node.address(),node.localWater()));
        assertEquals(joined,HydrologySphericalTopologyV2.route(
            sources,HydrologyGlobalSlopeRouterV2.planetElevations(terrain),Set.of()));
        HydrologyConservationV2.verify(joined,HydrologyFlowGraphV2.solve(joined));
        Collections.reverse(regions);
        assertEquals(joined,HydrologyMosaicJoinV2.merge(
            HydrologyRegionSchedulerV2.load(regions,0,8,provider)));
    }

    @Test void checkpointOverlayCanRerouteAcrossUnloadedDateline() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(terrain);
        var source=at(7,3);
        var target=at(0,3);
        var edits=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var neighbor:HydrologySphericalTopologyV2.neighbors(source))
            edits.put(neighbor,1000.0);
        edits.put(source,0.0);
        edits.put(target,-1000.0);
        var restored=HydrologyTerrainOverlayV2.decode(
            HydrologyTerrainOverlayV2.encode(
                new HydrologyTerrainOverlayV2.State("world",edits)));
        var provider=new HydrologySphericalSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,1,Set.of(),restored);
        var west=List.copyOf(provider.load(new HydrologyRegionSchedulerV2.Region(3,1)));
        var east=List.copyOf(provider.load(new HydrologyRegionSchedulerV2.Region(0,1)));
        assertEquals(target,west.stream().filter(n->n.address().equals(source))
            .findFirst().orElseThrow().downstream());
        assertTrue(east.stream().anyMatch(n->n.address().equals(target)));
        var joined=HydrologyMosaicJoinV2.merge(List.of(west,east));
        HydrologyConservationV2.verify(joined,HydrologyFlowGraphV2.solve(joined));
        assertEquals(new HydrologyRegionSchedulerV2.Region(0,1),
            new HydrologyRegionSchedulerV2.Region(
                target.x()==0?0:(target.x()-1)/DIVISIONS,
                (target.y()-1)/DIVISIONS));
    }

    @Test void verifiedPolarAndSeamOutletsAreOwnedOnce() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),5);
        var climate=new PlanetClimateV2(terrain);
        var provider=new HydrologySphericalSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,1,Set.of(at(0,0),at(0,3)));
        var top=List.copyOf(provider.load(new HydrologyRegionSchedulerV2.Region(0,0)));
        var south=List.copyOf(provider.load(new HydrologyRegionSchedulerV2.Region(0,1)));
        assertEquals(1,top.stream().filter(HydrologyFlowGraphV2.Node::terminal).count());
        assertEquals(1,south.stream().filter(HydrologyFlowGraphV2.Node::terminal).count());
        assertTrue(List.copyOf(provider.load(new HydrologyRegionSchedulerV2.Region(3,1)))
            .stream().noneMatch(HydrologyFlowGraphV2.Node::terminal));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySphericalSlopeRegionProviderV2(
                terrain,climate,LEVEL,DIVISIONS,1,Set.of(at(8,3))));
        assertThrows(IllegalArgumentException.class,()->
            provider.load(new HydrologyRegionSchedulerV2.Region(4,1)));
    }
}
