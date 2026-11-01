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

    @Test void checkpointRestoredErosionChangesRoutesAcrossUnloadedNeighbor() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(terrain);
        var tile=new HydrologyRegionSchedulerV2.Region(1,1);
        var original=new HydrologySeededSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,1,Set.of());
        var first=List.copyOf(original.load(tile)).get(0);
        var from=first.address();
        var west=new GlobalHydrologyAddressV2.Sample(
            LEVEL,DIVISIONS,from.x()-1,from.y());
        var east=new GlobalHydrologyAddressV2.Sample(
            LEVEL,DIVISIONS,from.x()+1,from.y());
        var chosen=west.equals(first.downstream())?east:west;
        double elevation=HydrologyGlobalSlopeRouterV2.planetElevations(terrain).at(from);
        var edited=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            if(dx==0&&dy==0)continue;
            edited.put(new GlobalHydrologyAddressV2.Sample(
                LEVEL,DIVISIONS,from.x()+dx,from.y()+dy),elevation+1000);
        }
        edited.put(chosen,elevation-1000);
        var saved=new HydrologyTerrainOverlayV2.State("world",edited);
        var restored=HydrologyTerrainOverlayV2.decode(
            HydrologyTerrainOverlayV2.encode(saved));
        var changed=new HydrologySeededSlopeRegionProviderV2(
            terrain,climate,LEVEL,DIVISIONS,1,Set.of(),restored);
        var changedFirst=List.copyOf(changed.load(tile)).get(0);
        assertEquals(from,changedFirst.address());
        assertEquals(chosen,changedFirst.downstream());
        assertNotEquals(first.downstream(),changedFirst.downstream());
        assertEquals(List.copyOf(changed.load(tile)),List.copyOf(changed.load(tile)));
        assertEquals(first,List.copyOf(original.load(tile)).get(0));
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
