package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalCellAreaV2Test {
    private static GlobalHydrologyAddressV2.Sample at(int level,int divisions,
                                                       long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(level,divisions,x,y);
    }

    private static List<GlobalHydrologyAddressV2.Sample> sphere(int level,
                                                                 int divisions) {
        long nx=(2L<<level)*divisions,ny=(1L<<level)*divisions;
        var samples=new ArrayList<GlobalHydrologyAddressV2.Sample>();
        samples.add(at(level,divisions,0,0));
        for(long y=1;y<ny;y++)for(long x=0;x<nx;x++)
            samples.add(at(level,divisions,x,y));
        samples.add(at(level,divisions,0,ny));
        return samples;
    }

    @Test void completeCanonicalSphereOwnsExactlyFourPiRadiusSquared() {
        double radius=6_371_000;
        for(int level=0;level<=3;level++)for(int divisions:new int[]{1,2,4}) {
            var samples=sphere(level,divisions);
            double sum=samples.stream().mapToDouble(s->
                HydrologySphericalCellAreaV2.areaAt(s,radius)).sum();
            assertEquals(HydrologySphericalCellAreaV2.planetArea(radius),
                         sum,0.00000000001*sum);
            long nx=(2L<<level)*divisions,ny=(1L<<level)*divisions;
            assertEquals(nx*(ny-1)+2,samples.size());
            assertEquals(HydrologySphericalCellAreaV2.areaAt(
                at(level,divisions,0,0),radius),
                HydrologySphericalCellAreaV2.areaAt(
                at(level,divisions,0,ny),radius),0.0001);
        }
    }

    @Test void highResolutionPolarCapsRemainPositiveAndNoSeamDuplicates() {
        var pole=at(30,128,0,0);
        assertTrue(HydrologySphericalCellAreaV2.areaAt(pole,6_371_000)>0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalCellAreaV2.areaAt(at(1,2,8,2),6_371_000));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalCellAreaV2.areaAt(at(1,2,4,0),6_371_000));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalCellAreaV2.areaAt(at(1,2,0,2),0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalCellAreaV2.planetArea(Double.MAX_VALUE));
    }

    @Test void precipitationDepthAcrossIndependentOwnedCellsConservesGlobalVolume() {
        double radius=6_371_000,depth=0.01;
        var forcing=new HashMap<GlobalHydrologyAddressV2.Sample,
                                HydrologySnowSoilStateV2.Forcing>();
        for(var sample:sphere(1,2))
            forcing.put(sample,HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                sample,radius,depth,15,0,0,1,0));
        var advanced=HydrologySnowSoilStateV2.advance(
            new HydrologySnowSoilStateV2.State("world",Map.of()),forcing);
        double expected=depth*HydrologySphericalCellAreaV2.planetArea(radius);
        assertEquals(expected,advanced.precipitationVolume(),expected*1e-12);
        assertEquals(expected,advanced.runoffVolume(),expected*1e-12);
        assertEquals(0,advanced.storedVolume(),1e-9);
        assertEquals(26,advanced.runoff().size());
        assertEquals(26,advanced.state().cells().size());
        assertEquals(expected,advanced.runoff().stream()
            .mapToDouble(HydrologyGlobalSlopeRouterV2.Source::localWater)
            .sum(),expected*1e-12);
    }
}
