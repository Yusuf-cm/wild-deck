package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class PlanetSurfaceV2Test {
    @Test void v1RemainsTheMacroTerrainReference() {
        var planet=Planet.emptyEarthSized();
        var v1=new PlanetSurfaceGenerator(planet,42);
        var v2=new PlanetSurfaceV2(planet,42);
        for(int lat=-90;lat<=90;lat+=15)for(int lon=-180;lon<180;lon+=20){
            var p=new Planet.SurfacePoint(lat,lon);
            assertEquals(v1.sample(p).elevationMeters(),v2.sample(p).elevationMeters());
        }
        assertEquals("planet-surface-v1",PlanetSurfaceGenerator.VERSION);
        assertEquals("planet-surface-v2",PlanetSurfaceV2.VERSION);
    }
    @Test void regionEdgesMatchExactly() {
        var generator=new PlanetSurfaceV2(Planet.emptyEarthSized(),-7);
        for(int level=0;level<=6;level++){
            var left=new PlanetRegionGrid.Region(level,0,0);
            var right=new PlanetRegionGrid.Region(level,1,0);
            for(int y=0;y<=8;y++){
                assertEquals(generator.sample(left,8,y,8).elevationMeters(),
                             generator.sample(right,0,y,8).elevationMeters());
            }
        }
    }
    @Test void parentAndChildSamplesCoincide() {
        var generator=new PlanetSurfaceV2(Planet.emptyEarthSized(),1598);
        var parent=new PlanetRegionGrid.Region(3,5,4);
        var child=new PlanetRegionGrid.Region(4,10,8);
        assertEquals(parent,PlanetRegionGrid.parent(child));
        assertEquals(generator.sample(parent,0,0,8).elevationMeters(),
                     generator.sample(child,0,0,8).elevationMeters());
        assertEquals(generator.sample(parent,4,4,8).elevationMeters(),
                     generator.sample(child,8,8,8).elevationMeters());
    }
    @Test void seedChannelsUseAllBitsAndDoNotDependOnSamplingOrder() {
        var generator=new PlanetSurfaceV2(Planet.emptyEarthSized(),Long.MIN_VALUE);
        assertEquals(generator.layerSeed(9),generator.layerSeed(9));
        assertNotEquals(generator.layerSeed(9),generator.layerSeed(10));
        assertNotEquals(generator.layerSeed(9),
            new PlanetSurfaceV2(Planet.emptyEarthSized(),0).layerSeed(9));
        var point=new Planet.SurfacePoint(12.25,-179.75);
        var expected=generator.sample(point);
        IntStream.range(0,200).parallel().forEach(i ->
            generator.sample(new Planet.SurfacePoint((i%181)-90,(i%360)-180)));
        assertEquals(expected,generator.sample(point));
    }
    @Test void invalidRegionsAndSamplesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new PlanetRegionGrid.Region(31,0,0));
        assertThrows(IllegalArgumentException.class,()->new PlanetRegionGrid.Region(0,2,0));
        assertThrows(IllegalArgumentException.class,()->PlanetRegionGrid.point(new PlanetRegionGrid.Region(0,0,0),-1,0,8));
        assertThrows(IllegalArgumentException.class,()->PlanetRegionGrid.point(new PlanetRegionGrid.Region(0,0,0),0,0,0));
        assertThrows(IllegalArgumentException.class,()->PlanetRegionGrid.parent(new PlanetRegionGrid.Region(0,0,0)));
    }
    @Test void dateLineCoordinatesAreEquivalent() {
        var generator=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        assertEquals(generator.sample(new Planet.SurfacePoint(20,-180)).elevationMeters(),
                     generator.sample(new Planet.SurfacePoint(20,180)).elevationMeters());
    }
}
