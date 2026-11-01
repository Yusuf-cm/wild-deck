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
        var wrongTotal=new HydrologySnowSoilStateV2.Step(valid.state(),valid.runoff(),
            valid.initialVolume(),valid.precipitationVolume(),
            valid.evaporationVolume(),valid.runoffVolume()+1_000,
            valid.storedVolume());
        assertThrows(IllegalArgumentException.class,()->preview(wrongTotal));
        var missingStore=new HydrologySnowSoilStateV2.Step(
            new HydrologySnowSoilStateV2.State("world",Map.of()),
            valid.runoff(),valid.initialVolume(),valid.precipitationVolume(),
            valid.evaporationVolume(),valid.runoffVolume(),valid.storedVolume());
        assertThrows(IllegalArgumentException.class,()->preview(missingStore));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalErosionPreviewV2.calculate(
                new HydrologyTerrainOverlayV2.State("world",Map.of()),
                addresses,5,5,seed(),valid,-1,2000,.1,.4,
                capacity(),Set.of(24)));
    }

    @Test void checkedForcingPreviewReplaysTheSameSeasonAndSediment() {
        var addresses=window();
        var weather=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                      HydrologySnowSoilStateV2.Forcing>();
        for(var address:addresses)
            weather.put(address,HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address,RADIUS,2,5,0,0,1,10));
        var stores=new HydrologySnowSoilStateV2.State("world",Map.of());
        var terrain=new HydrologyTerrainOverlayV2.State("world",Map.of());
        var expected=HydrologySnowSoilStateV2.advance(stores,weather);
        var first=HydrologySeasonalErosionPreviewV2.calculateFromForcing(
            terrain,addresses,5,5,seed(),stores,weather,
            RADIUS,2000,.1,.4,capacity(),Set.of(24));
        assertEquals(expected,first.season());
        assertEquals(preview(expected).terrain().state(),first.erosion().terrain().state());
        assertEquals(expected.runoffVolume(),first.erosion().terminalVolume(),1e-6);
        var reversed=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                       HydrologySnowSoilStateV2.Forcing>();
        for(int i=addresses.size()-1;i>=0;i--)
            reversed.put(addresses.get(i),weather.get(addresses.get(i)));
        var replay=HydrologySeasonalErosionPreviewV2.calculateFromForcing(
            terrain,addresses,5,5,seed(),stores,reversed,
            RADIUS,2000,.1,.4,capacity(),Set.of(24));
        assertEquals(first.season(),replay.season());
        assertEquals(first.erosion().terrain().state(),replay.erosion().terrain().state());
    }

    @Test void rejectsCrossWorldAndForgedSeasonalWaterBudget() {
        var valid=season(window(),2);
        var foreign=new HydrologySnowSoilStateV2.Step(
            new HydrologySnowSoilStateV2.State("other-world",valid.state().cells()),
            valid.runoff(),valid.initialVolume(),valid.precipitationVolume(),
            valid.evaporationVolume(),valid.runoffVolume(),valid.storedVolume());
        assertThrows(IllegalArgumentException.class,()->preview(foreign));
        var forged=new HydrologySnowSoilStateV2.Step(
            valid.state(),valid.runoff(),valid.initialVolume(),
            valid.precipitationVolume()+100,valid.evaporationVolume(),
            valid.runoffVolume(),valid.storedVolume());
        assertThrows(IllegalArgumentException.class,()->preview(forged));
        var invalid=new HydrologySnowSoilStateV2.Step(
            valid.state(),valid.runoff(),valid.initialVolume(),
            Double.NaN,valid.evaporationVolume(),
            valid.runoffVolume(),valid.storedVolume());
        assertThrows(IllegalArgumentException.class,()->preview(invalid));
    }

    @Test void checkedForcingRejectsRadiusDriftMissingCellsAndWrongWorld() {
        var addresses=window();
        var weather=new HashMap<GlobalHydrologyAddressV2.Sample,
                                HydrologySnowSoilStateV2.Forcing>();
        for(var address:addresses)
            weather.put(address,HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address,RADIUS,2,5,0,0,1,10));
        var stores=new HydrologySnowSoilStateV2.State("world",Map.of());
        var terrain=new HydrologyTerrainOverlayV2.State("world",Map.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalErosionPreviewV2.calculateFromForcing(
                terrain,addresses,5,5,seed(),stores,weather,
                RADIUS+1,2000,.1,.4,capacity(),Set.of(24)));
        var missing=new HashMap<>(weather);
        missing.remove(addresses.get(0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalErosionPreviewV2.calculateFromForcing(
                terrain,addresses,5,5,seed(),stores,missing,
                RADIUS,2000,.1,.4,capacity(),Set.of(24)));
        var foreign=new HydrologySnowSoilStateV2.State("foreign",Map.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalErosionPreviewV2.calculateFromForcing(
                terrain,addresses,5,5,seed(),foreign,weather,
                RADIUS,2000,.1,.4,capacity(),Set.of(24)));
    }

    @Test void importedDischargeDrivesErosionWithoutDoubleCountingSeasonalRunoff() {
        var dry=season(window(),0);
        var source=window().get(0);
        var previous=new HydrologyTerrainOverlayV2.State("world",Map.of());
        var baseline=HydrologySeasonalErosionPreviewV2.calculate(
            previous,window(),5,5,seed(),dry,RADIUS,2000,.1,.4,
            capacity(),Set.of(24));
        var imported=HydrologySeasonalErosionPreviewV2.calculate(
            previous,window(),5,5,seed(),dry,RADIUS,2000,.1,.4,
            capacity(),Set.of(24),Map.of(source,500.0));
        assertEquals(0,imported.runoffVolume(),0);
        assertEquals(500,imported.terminalVolume(),1e-6);
        assertEquals(0,baseline.terrain().landscape().erodedMass(),0);
        assertTrue(imported.terrain().landscape().erodedMass()>0);
        assertEquals(500,
            imported.terrain().landscape().discharge().flow()[24],1e-6);
        assertTrue(previous.elevations().isEmpty());
        assertEquals(imported.terrain().state(),
            HydrologySeasonalErosionPreviewV2.calculate(
                previous,window(),5,5,seed(),dry,RADIUS,2000,.1,.4,
                capacity(),Set.of(24),Map.of(source,500.0)).terrain().state());
    }

    @Test void importedDischargeRejectsUnownedNegativeAndOverflowWater() {
        var dry=season(window(),0);
        var previous=new HydrologyTerrainOverlayV2.State("world",Map.of());
        var unknown=new GlobalHydrologyAddressV2.Sample(2,4,3,4);
        for(var incoming:List.of(
                Map.of(unknown,1.0),
                Map.of(window().get(0),-1.0),
                Map.of(window().get(0),Double.POSITIVE_INFINITY),
                Map.of(window().get(0),Double.MAX_VALUE,
                       window().get(1),Double.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class,()->
                HydrologySeasonalErosionPreviewV2.calculate(
                    previous,window(),5,5,seed(),dry,RADIUS,2000,.1,.4,
                    capacity(),Set.of(24),incoming));
        }
    }

    @Test void drySeasonDoesNotInventWaterOrSediment() {
        var dry=preview(season(window(),0));
        assertEquals(0,dry.runoffVolume(),0);
        assertEquals(0,dry.terminalVolume(),0);
        assertEquals(0,dry.terrain().landscape().erodedMass(),0);
        assertEquals(0,dry.terrain().landscape().depositedMass(),0);
    }
}
