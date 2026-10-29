package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTerrainWindowV2Test {
    private static double sum(Collection<Double> values){
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    @Test void seededConnectedRegionsConserveRainAndNeverDuplicateSharedVertices(){
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),20261029);
        var climate=new PlanetClimateV2(world);
        var a=HydrologyTerrainWindowV2.generate(world,climate,2,1,1,2,2,4,1);
        var b=HydrologyTerrainWindowV2.generate(world,climate,2,1,1,2,2,4,1);
        assertEquals(a.nodes(),b.nodes());
        assertEquals(81,a.nodes().size()); // 9x9, not four separate 5x5 grids
        assertEquals(81,new HashSet<>(a.nodes().stream().map(
            HydrologyFlowGraphV2.Node::address).toList()).size());
        var solved=HydrologyFlowGraphV2.solve(a.nodes());
        double source=Arrays.stream(a.hydrology().terrain().rainfall()).sum();
        assertEquals(source,sum(solved.terminalOutflow().values())+
                            sum(solved.pendingOutflow().values()),1e-6);
    }

    @Test void overlappingWindowsShareLocationAndRainfallButNotAssumedRoutes(){
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(world);
        var left=HydrologyTerrainWindowV2.generate(world,climate,2,1,1,2,1,4,1);
        var right=HydrologyTerrainWindowV2.generate(world,climate,2,2,1,2,1,4,1);
        var a=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var node:left.nodes())a.put(node.address(),node.localWater());
        int shared=0;
        for(var node:right.nodes())if(a.containsKey(node.address())){
            assertEquals(a.get(node.address()),node.localWater(),0);
            shared++;
        }
        assertEquals(25,shared); // one whole overlapping 5x5 region
    }

    @Test void enforcesGraphResolutionAndTerrainBounds(){
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(world);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTerrainWindowV2.generate(world,climate,2,1,1,1,1,129,1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTerrainWindowV2.generate(world,climate,2,7,3,2,2,4,1));
    }
}
