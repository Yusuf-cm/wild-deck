package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalHydrologyV2Test {
    @Test void composedHydrologyIsReproducible(){
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(world);
        var a=RegionalHydrologyV2.generate(world,climate,4,5,4,2,2,8,2);
        var b=RegionalHydrologyV2.generate(world,climate,4,5,4,2,2,8,2);
        assertArrayEquals(a.terrain().elevation(),b.terrain().elevation());
        assertArrayEquals(a.lakes().waterSurface(),b.lakes().waterSurface());
        assertArrayEquals(a.lakes().spillTo(),b.lakes().spillTo());
        assertEquals(a.rivers(),b.rivers());
        assertArrayEquals(a.spillDischarge().flow(),b.spillDischarge().flow());
        assertArrayEquals(a.spillDischarge().outlet(),b.spillDischarge().outlet());
        double totalRain=0,totalOutflow=0;
        double[] rainfall=a.terrain().rainfall(),flow=a.spillDischarge().flow();
        int[] spill=a.lakes().spillTo();
        for(int i=0;i<rainfall.length;i++){
            totalRain+=rainfall[i];
            if(spill[i]<0)totalOutflow+=flow[i];
        }
        assertEquals(totalRain,totalOutflow,1e-7);
        for(int i=0;i<a.terrain().elevation().length;i++)
            assertTrue(a.lakes().waterSurface()[i]>=a.terrain().elevation()[i]);
    }
}
