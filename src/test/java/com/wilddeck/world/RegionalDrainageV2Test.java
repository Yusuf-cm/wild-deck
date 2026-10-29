package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class RegionalDrainageV2Test {
    @Test void downhillChainConservesRainfall(){
        var drainage=RegionalDrainageV2.route(3,1,new double[]{3,2,1},new double[]{1,2,3});
        assertEquals(1,drainage.downstreamAt(0,0));
        assertEquals(2,drainage.downstreamAt(1,0));
        assertEquals(-1,drainage.downstreamAt(2,0));
        assertEquals(6,drainage.accumulationAt(2,0));
        assertTrue(drainage.sinkAt(2,0));
    }
    @Test void localBasinIsAnExplicitSink(){
        var drainage=RegionalDrainageV2.route(3,3,new double[]{4,4,4,4,0,4,4,4,4},
            new double[]{1,1,1,1,1,1,1,1,1});
        assertEquals(-1,drainage.downstreamAt(1,1));
        assertEquals(9,drainage.accumulationAt(1,1));
    }
    @Test void flatTerrainDoesNotCreateCycles(){
        var drainage=RegionalDrainageV2.route(3,3,new double[9],new double[]{1,1,1,1,1,1,1,1,1});
        assertTrue(Arrays.stream(drainage.downstream()).allMatch(i->i==-1));
    }
    @Test void generatedDrainageIsDeterministicAndBounded(){
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(world);
        var region=new PlanetRegionGrid.Region(4,7,5);
        var a=RegionalDrainageV2.sample(world,climate,region,8);
        var b=RegionalDrainageV2.sample(world,climate,region,8);
        assertArrayEquals(a.downstream(),b.downstream());
        assertArrayEquals(a.accumulatedRain(),b.accumulatedRain());
        assertTrue(Arrays.stream(a.downstream()).allMatch(i->i>=-1&&i<81));
        assertTrue(Arrays.stream(a.accumulatedRain()).allMatch(v->Double.isFinite(v)&&v>=0));
    }
    @Test void invalidRainfallAndShapesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->RegionalDrainageV2.route(2,2,new double[4],new double[3]));
        assertThrows(IllegalArgumentException.class,()->RegionalDrainageV2.route(1,1,new double[]{0},new double[]{-1}));
        assertThrows(IllegalArgumentException.class,()->RegionalDrainageV2.route(1,1,new double[]{Double.NaN},new double[]{1}));
    }
}
