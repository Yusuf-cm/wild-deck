package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectedDrainageV2Test {
    private final PlanetSurfaceV2 world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
    private final PlanetClimateV2 climate=new PlanetClimateV2(world);

    @Test void mosaicSamplesMatchIndividualRegionsAtSeams(){
        var mosaic=ConnectedDrainageV2.generate(world,climate,4,5,4,2,2,8);
        assertEquals(17,mosaic.width());assertEquals(17,mosaic.height());
        for(int y=0;y<=8;y++){
            var point=PlanetRegionGrid.point(new PlanetRegionGrid.Region(4,6,4),0,y,8);
            assertEquals(world.sample(point).elevationMeters(),mosaic.elevation()[y*17+8]);
        }
        for(int x=0;x<=8;x++){
            var point=PlanetRegionGrid.point(new PlanetRegionGrid.Region(4,5,5),x,0,8);
            assertEquals(world.sample(point).elevationMeters(),mosaic.elevation()[8*17+x]);
        }
    }
    @Test void mosaicDoesNotDependOnGenerationOrder(){
        var a=ConnectedDrainageV2.generate(world,climate,4,5,4,2,2,8);
        var b=ConnectedDrainageV2.generate(world,climate,4,5,4,2,2,8);
        assertArrayEquals(a.elevation(),b.elevation());
        assertArrayEquals(a.rainfall(),b.rainfall());
        assertArrayEquals(a.drainage().downstream(),b.drainage().downstream());
        assertArrayEquals(a.drainage().accumulatedRain(),b.drainage().accumulatedRain());
    }
    @Test void drainageIndicesStayWithinMosaic(){
        var mosaic=ConnectedDrainageV2.generate(world,climate,4,5,4,3,2,8);
        int count=mosaic.width()*mosaic.height();
        for(int index:mosaic.drainage().downstream())assertTrue(index>=-1&&index<count);
    }
    @Test void rejectsMosaicOutsidePlanet(){
        assertThrows(IllegalArgumentException.class,
            ()->ConnectedDrainageV2.generate(world,climate,1,3,0,2,1,8));
        assertThrows(IllegalArgumentException.class,
            ()->ConnectedDrainageV2.generate(world,climate,1,0,0,1,1,0));
    }
}
