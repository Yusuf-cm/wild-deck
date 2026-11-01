package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.wilddeck.world.PlanetSurfaceGenerator.Resource.*;

class PlanetSurfaceGeneratorTest {
    private PlanetSurfaceGenerator generator(long seed){return new PlanetSurfaceGenerator(Planet.emptyEarthSized(),seed);}
    private Planet.SurfacePoint point(double lat,double lon){return new Planet.SurfacePoint(lat,lon);}

    @Test void samplingIsRepeatableRegardlessOfRequestOrderAndPreviewResolution(){
        var g=generator(42);var before=g.sample(point(37.5,-122.5));
        for(int i=0;i<40;i++)g.sample(point(i-20,i*7-150));
        g.atlas(180);g.atlas(360);
        assertEquals(before,g.sample(point(37.5,-122.5)));
        assertEquals(before,generator(42).sample(point(37.5,-122.5)));
        assertEquals(g.atlas(180).toJson(),generator(42).atlas(180).toJson());
    }
    @Test void differentAndHighBitSeedsProduceDifferentWorlds(){
        var first=generator(42).atlas(180);var second=generator(1598).atlas(180);
        assertFalse(Arrays.equals(first.elevation(),second.elevation()));
        assertFalse(Arrays.equals(first.elevation(),generator(42+(1L<<48)).atlas(180).elevation()));
        assertFalse(Arrays.equals(first.elevation(),generator(Long.MIN_VALUE).atlas(180).elevation()));
    }
    @Test void longitudeSeamAndPolesAreGeometricallyContinuous(){
        var g=generator(-7);
        for(int latitude=-90;latitude<=90;latitude+=10){
            assertEquals(g.sample(point(latitude,-180)),g.sample(point(latitude,180)));
            var left=g.sample(point(latitude,-179.99999));var right=g.sample(point(latitude,179.99999));
            assertTrue(Math.abs(left.elevationMeters()-right.elevationMeters())<=2);
        }
        for(double pole:new double[]{-90,90})for(int lon=-180;lon<=180;lon+=15)
            assertEquals(g.sample(point(pole,0)),g.sample(point(pole,lon)));
    }
    @Test void depositsRespectTerrainAndRemainBounded(){
        for(long seed:new long[]{42,1598,-7}){
            var atlas=generator(seed).atlas(180);int[] elevation=atlas.elevation();
            int[] iron=atlas.iron(),copper=atlas.copper(),stormglass=atlas.stormglass();
            var fields=List.of(iron,copper,stormglass);
            assertTrue(atlas.landFraction()>0&&atlas.landFraction()<1);
            for(int i=0;i<elevation.length;i++){
                assertTrue(elevation[i]>=-11000&&elevation[i]<=9000);
                for(int[] field:fields){
                    assertTrue(field[i]>=0&&field[i]<=100);
                    if(elevation[i]<=0)assertEquals(0,field[i]);
                }
                if(elevation[i]<=300)assertEquals(0,iron[i]);
                if(elevation[i]<=30||elevation[i]>=3000)assertEquals(0,copper[i]);
                if(elevation[i]<=1000)assertEquals(0,stormglass[i]);
            }
            for(int[] field:fields)assertTrue(Arrays.stream(field).anyMatch(n->n>0));
        }
    }
    @Test void resourceSignalsFormClustersRatherThanUniformSprinkles(){
        var atlas=generator(42).atlas(360);
        for(int[] field:List.of(atlas.iron(),atlas.copper(),atlas.stormglass())){
            int deposits=0,adjacent=0;
            for(int row=0;row<atlas.height();row++)for(int col=0;col<atlas.width();col++){
                int i=row*atlas.width()+col;
                if(field[i]>0){deposits++;if(field[row*atlas.width()+(col+1)%atlas.width()]>0)adjacent++;}
            }
            double overall=(double)deposits/field.length;
            assertTrue((double)adjacent/deposits>overall*2,"Expected locally clustered deposits");
        }
    }
    @Test void changingDepositArraysCannotMutateGeneratedAtlas(){
        var atlas=generator(42).atlas(180);String before=atlas.toJson();
        Arrays.fill(atlas.elevation(),123);Arrays.fill(atlas.iron(),100);
        assertEquals(before,atlas.toJson());
        assertThrows(IllegalArgumentException.class,()->generator(42).atlas(10000));
    }
    @Test void worldScaleAndSeedMetadataArePreservedExactly(){
        var atlas=generator(Long.MAX_VALUE).atlas(180);
        assertEquals(Long.MAX_VALUE,atlas.seed());assertEquals(6371,atlas.radiusKm());
        assertTrue(atlas.toJson().contains("\"seed\":\"9223372036854775807\""));
        assertTrue(atlas.toJson().contains("\"generatorVersion\":\"planet-surface-v1\""));
    }
    @Test void versionOneRetainsItsReferenceWorld()throws Exception{
        var atlas=generator(42).atlas(360);
        var buffer=ByteBuffer.allocate(360*180*4*4);
        for(int[] field:List.of(atlas.elevation(),atlas.iron(),atlas.copper(),atlas.stormglass()))
            for(int value:field)buffer.putInt(value);
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.array()));
        assertEquals("0e86257feb5d961dde44ab3fd6215a3abdceede83cc900a0a19c51e41a75b901",fingerprint,
            "Changing this world requires an explicit generator-version decision");
    }
}
