package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeededSlopeRegionProviderV2Test {
    private static final int LEVEL=2, DIVISIONS=4;

    @Test void adjacentIndependentTilesMatchCombinedGlobalRoutingWithoutDoubleRain() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),987654321L);
        var climate=new PlanetClimateV2(terrain);
        var provider=new HydrologySeededSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,3.0,Set.of());
        var west=new HydrologyRegionSchedulerV2.Region(1,1);
        var east=new HydrologyRegionSchedulerV2.Region(2,1);
        var a=List.copyOf(provider.load(west));
        var b=List.copyOf(provider.load(east));
        assertEquals(16,a.size());
        assertEquals(16,b.size());
        assertEquals(a,List.copyOf(provider.load(west)));
        var loaded=HydrologyRegionSchedulerV2.load(List.of(east,west),0,2,provider);
        var joined=HydrologyMosaicJoinV2.merge(loaded);
        assertEquals(32,joined.size());
        var unique=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:joined)assertTrue(unique.add(node.address()));
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        for(var node:joined)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(
                node.address(),node.localWater()));
        var global=HydrologyGlobalSlopeRouterV2.route(
            sources,HydrologyGlobalSlopeRouterV2.planetElevations(terrain),Set.of());
        assertEquals(global,joined);
        HydrologyConservationV2.verify(joined,HydrologyFlowGraphV2.solve(joined));
    }

    @Test void verifiedOutletsAreOnlyTerminalInOwningTile() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(terrain);
        var outlet=new GlobalHydrologyAddressV2.Sample(LEVEL,DIVISIONS,8,8);
        var provider=new HydrologySeededSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,0,Set.of(outlet));
        var left=List.copyOf(provider.load(new HydrologyRegionSchedulerV2.Region(1,1)));
        var right=List.copyOf(provider.load(new HydrologyRegionSchedulerV2.Region(2,1)));
        assertEquals(1,left.stream().filter(HydrologyFlowGraphV2.Node::terminal).count());
        assertTrue(left.stream().anyMatch(n->n.address().equals(outlet)&&n.terminal()));
        assertTrue(right.stream().noneMatch(HydrologyFlowGraphV2.Node::terminal));
    }

    @Test void failsClosedAtUnresolvedPlanetEdgesAndBadInputs() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(terrain);
        var provider=new HydrologySeededSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,1,Set.of());
        assertThrows(IllegalArgumentException.class,()->
            provider.load(new HydrologyRegionSchedulerV2.Region(0,1)));
        assertThrows(IllegalArgumentException.class,()->
            provider.load(new HydrologyRegionSchedulerV2.Region(1,0)));
        assertThrows(IllegalArgumentException.class,()->
            provider.load(new HydrologyRegionSchedulerV2.Region(999,1)));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySeededSlopeRegionProviderV2(
                terrain,climate,LEVEL,DIVISIONS,Double.NaN,Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySeededSlopeRegionProviderV2(
                terrain,climate,LEVEL,DIVISIONS,1,Set.of(
                    new GlobalHydrologyAddressV2.Sample(1,DIVISIONS,2,2))));
    }
}
