package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wilddeck.world.Planet.SurfacePoint;

class PlanetTest {
    private final Planet planet=Planet.emptyEarthSized();

    @Test void earthSizedSurfaceUsesKilometresWithoutAllocatingRegions(){
        assertEquals(12742,planet.diameterKm(),0.01);
        assertEquals(40030.17,planet.circumferenceKm(),0.01);
        assertEquals(510064472,planet.surfaceAreaKm2(),1);
        assertEquals(0,planet.surfaceDistanceKm(new SurfacePoint(12,40),new SurfacePoint(12,40)),1e-9);
    }
    @Test void longitudeSeamWrapsAndItsTwoAddressesAreIdentical(){
        assertEquals(new SurfacePoint(0,-180),new SurfacePoint(0,180));
        double shortCrossing=planet.surfaceDistanceKm(new SurfacePoint(0,179),new SurfacePoint(0,-179));
        double ordinary=planet.surfaceDistanceKm(new SurfacePoint(0,-1),new SurfacePoint(0,1));
        assertEquals(ordinary,shortCrossing,1e-8);
        assertTrue(shortCrossing<223);
    }
    @Test void polesAndOppositeSidesHaveCorrectGreatCircleDistances(){
        assertEquals(planet.circumferenceKm()/2,
            planet.surfaceDistanceKm(new SurfacePoint(-90,0),new SurfacePoint(90,0)),1e-7);
        assertEquals(planet.circumferenceKm()/4,
            planet.surfaceDistanceKm(new SurfacePoint(0,0),new SurfacePoint(90,120)),1e-7);
        assertEquals(0,planet.surfaceDistanceKm(new SurfacePoint(90,-170),new SurfacePoint(90,80)),1e-7);
    }
    @Test void cartesianCoordinatesStayOnTheSphereAndUseDeclaredAxes(){
        var north=planet.toCartesianKm(new SurfacePoint(90,0));
        assertEquals(planet.radiusKm(),north.zKm(),1e-8);
        var east=planet.toCartesianKm(new SurfacePoint(0,90));
        assertEquals(planet.radiusKm(),east.yKm(),1e-8);assertEquals(0,east.xKm(),1e-8);
        var point=planet.toCartesianKm(new SurfacePoint(-37,151));
        assertEquals(planet.radiusKm(),Math.sqrt(point.xKm()*point.xKm()+point.yKm()*point.yKm()+point.zKm()*point.zKm()),1e-8);
    }
    @Test void invalidCoordinatesAndPlanetDimensionsAreRejected(){
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,91,-91})
            assertThrows(IllegalArgumentException.class,()->new SurfacePoint(invalid,0));
        for(double invalid:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,181,-181})
            assertThrows(IllegalArgumentException.class,()->new SurfacePoint(0,invalid));
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new Planet("Empty",invalid));
        assertThrows(IllegalArgumentException.class,()->new Planet(" ",6371));
    }
    @Test void distancesAreSymmetricAndScaleWithPlanetSize(){
        var from=new SurfacePoint(-24,168);var to=new SurfacePoint(41,-123);
        double distance=planet.surfaceDistanceKm(from,to);
        assertEquals(distance,planet.surfaceDistanceKm(to,from),1e-8);
        assertEquals(distance*2,new Planet("Larger",planet.radiusKm()*2).surfaceDistanceKm(from,to),1e-8);
        assertTrue(distance>0&&distance<=planet.circumferenceKm()/2);
    }
}
