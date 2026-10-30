package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalErosionPreviewV2Test {
    private static final double RADIUS=1000;
    private static List<GlobalHydrologyAddressV2.Sample> window() {
        var result=new ArrayList<GlobalHydrologyAddressV2.Sample>();
        for(int y=4;y<9;y++)for(int x=4;x<9;x++)
            result.add(new GlobalHydrologyAddressV2.Sample(2,4,x,y));
        return List.copyOf(result);
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return a->20-(a.x()-4)-(a.y()-4);
    }
    private static HydrologySnowSoilStateV2.Step season(
            List<GlobalHydrologyAddressV2.Sample> addresses,double rain) {
        var weather=new HashMap<GlobalHydrologyAddressV2.Sample,
                                HydrologySnowSoilStateV2.Forcing>();
        for(var address:addresses)
            weather.put(address,
                HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                    address,RADIUS,rain,5,0,0,1,10));
        return HydrologySnowSoilStateV2.advance(
            new HydrologySnowSoilStateV2.State("world",Map.of()),weather);
    }
    private static double[] capacity() {
        double[] result=new double[25];
        Arrays.fill(result,2_000_000);
        return result;
    }
    private static HydrologySeasonalErosionPreviewV2.Preview preview(
            HydrologySnowSoilStateV2.Step season) {
        return HydrologySeasonalErosionPreviewV2.calculate(
            new HydrologyTerrainOverlayV2.State("world",Map.of()),
            window(),5,5,seed(),season,RADIUS,2000,.1,.4,
            capacity(),Set.of(24));
    }

    @Test void conservesSeasonalRunoffAndSedimentWithoutMutatingSeed() {
        var snow=season(window(),2);
        var first=preview(snow);
        var second=preview(snow);
        assertEquals(snow.runoffVolume(),first.runoffVolume(),1e-6);
        assertEquals(first.runoffVolume(),first.terminalVolume(),1e-6);
        assertEquals(first.terrain().state(),second.terrain().state());
        assertEquals(25,first.terrain().state().elevations().size());
        assertTrue(first.terrain().landscape().erodedMass()>0);
        assertTrue(first.terrain().landscape().depositedMass()>0);
        assertEquals(first.terrain().landscape().erodedMass(),
            first.terrain().landscape().depositedMass()+
            first.terrain().landscape().exportedMass(),1e-5);
        assertEquals(20,seed().at(window().get(0)),0);
        assertEquals(first.terrain().state(),
            HydrologyTerrainOverlayV2.decode(
                HydrologyTerrainOverlayV2.encode(first.terrain().state())));
    }

    @Test void rejectsPartialOrDuplicatedSeasonalOwnership() {
        var addresses=window();
        var partial=season(addresses.subList(1,addresses.size()),2);
        assertThrows(IllegalArgumentException.class,()->preview(partial));
        var valid=season(addresses,2);
        var duplicate=new ArrayList<>(valid.runoff());
        duplicate.set(1,duplicate.get(0));
        var forged=new HydrologySnowSoilStateV2.Step(valid.state(),duplicate,
            valid.initialVolume(),valid.precipitationVolume(),
            valid.evaporationVolume(),valid.runoffVolume(),valid.storedVolume());
        assertThrows(IllegalArgumentException.class,()->preview(forged));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalErosionPreviewV2.calculate(
                new HydrologyTerrainOverlayV2.State("world",Map.of()),
                addresses,5,5,seed(),valid,-1,2000,.1,.4,
                capacity(),Set.of(24)));
    }

    @Test void drySeasonDoesNotInventWaterOrSediment() {
        var dry=preview(season(window(),0));
        assertEquals(0,dry.runoffVolume(),0);
        assertEquals(0,dry.terminalVolume(),0);
        assertEquals(0,dry.terrain().landscape().erodedMass(),0);
        assertEquals(0,dry.terrain().landscape().depositedMass(),0);
    }
}
