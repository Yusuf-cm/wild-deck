package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanetClimateV2Test {
    private PlanetClimateV2 climate(long seed){
        return new PlanetClimateV2(new PlanetSurfaceV2(Planet.emptyEarthSized(),seed));
    }
    @Test void climateIsRepeatableAndBounded(){
        var a=climate(42);var b=climate(42);
        for(int lat=-90;lat<=90;lat+=10)for(int lon=-180;lon<=180;lon+=15){
            var point=new Planet.SurfacePoint(lat,lon);
            var result=a.sample(point);
            assertEquals(result,b.sample(point));
            assertTrue(Double.isFinite(result.temperatureC()));
            assertTrue(result.moisture()>=0&&result.moisture()<=1);
        }
    }
    @Test void dateLineAndPolesAreContinuous(){
        var climate=climate(-7);
        for(int lat=-90;lat<=90;lat+=15)
            assertEquals(climate.sample(new Planet.SurfacePoint(lat,-180)),
                         climate.sample(new Planet.SurfacePoint(lat,180)));
        for(int lon=-180;lon<=180;lon+=30){
            assertEquals(climate.sample(new Planet.SurfacePoint(90,0)),
                         climate.sample(new Planet.SurfacePoint(90,lon)));
            assertEquals(climate.sample(new Planet.SurfacePoint(-90,0)),
                         climate.sample(new Planet.SurfacePoint(-90,lon)));
        }
    }
    @Test void coldPolarBaselineAndElevationLapseRate(){
        var climate=climate(42);
        assertTrue(climate.sample(new Planet.SurfacePoint(90,0)).temperatureC()<0);
        assertTrue(climate.sample(new Planet.SurfacePoint(-90,0)).temperatureC()<0);
    }
    @Test void differentSeedsAlterMoisture(){
        var a=climate(42);var b=climate(1598);
        boolean different=false;
        for(int lat=-60;lat<=60;lat+=15)for(int lon=-150;lon<=150;lon+=30){
            var point=new Planet.SurfacePoint(lat,lon);
            if(Math.abs(a.sample(point).moisture()-b.sample(point).moisture())>1e-8)different=true;
        }
        assertTrue(different);
    }
}
