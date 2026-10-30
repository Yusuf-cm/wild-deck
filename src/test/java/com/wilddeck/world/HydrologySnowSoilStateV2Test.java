package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySnowSoilStateV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample POLE=at(0,0);
    private static final GlobalHydrologyAddressV2.Sample WEST=at(7,2);
    private static final GlobalHydrologyAddressV2.Sample EAST=at(0,2);
    private static Map<GlobalHydrologyAddressV2.Sample,
                       HydrologySnowSoilStateV2.Forcing> winter() {
        return Map.of(
            POLE,new HydrologySnowSoilStateV2.Forcing(3,-2,0,2,1,2,10),
            WEST,new HydrologySnowSoilStateV2.Forcing(2,2,0,1,1,2,10),
            EAST,new HydrologySnowSoilStateV2.Forcing(1,2,0,1,1,2,10));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
                       HydrologySnowSoilStateV2.Forcing> spring() {
        return Map.of(
            POLE,new HydrologySnowSoilStateV2.Forcing(0,3,1,1,1,2,10),
            WEST,new HydrologySnowSoilStateV2.Forcing(0,2,0,1,1,2,10),
            EAST,new HydrologySnowSoilStateV2.Forcing(1,2,0,1,1,2,10));
    }

    @Test void twoSeasonsAcrossDatelineAndPoleConserveActualVolumes() {
        var initial=new HydrologySnowSoilStateV2.State("world",Map.of());
        var winter=HydrologySnowSoilStateV2.advance(initial,winter());
        assertEquals(60,winter.precipitationVolume(),1e-10);
        assertEquals(10,winter.runoffVolume(),1e-10);
        assertEquals(50,winter.storedVolume(),1e-10);
        assertEquals(3,winter.state().cells().get(POLE).snowDepth(),1e-10);
        var spring=HydrologySnowSoilStateV2.advance(winter.state(),spring());
        assertEquals(50,spring.initialVolume(),1e-10);
        assertEquals(10,spring.precipitationVolume(),1e-10);
        assertEquals(10,spring.evaporationVolume(),1e-10);
        assertEquals(20,spring.runoffVolume(),1e-10);
        assertEquals(30,spring.storedVolume(),1e-10);
        assertEquals(0,spring.state().cells().get(POLE).snowDepth(),1e-10);
        assertEquals(70,winter.runoffVolume()+spring.runoffVolume()+
            winter.evaporationVolume()+spring.evaporationVolume()+
            spring.storedVolume(),1e-10);
        assertEquals(List.of(POLE,EAST,WEST),spring.runoff().stream()
            .map(HydrologyGlobalSlopeRouterV2.Source::address).toList());
        assertEquals(20,spring.runoff().stream().mapToDouble(
            HydrologyGlobalSlopeRouterV2.Source::localWater).sum(),1e-10);
    }

    @Test void durableRestartCarriesSnowAndSoilWithoutReinjectingRain()
            throws IOException {
        var initial=new HydrologySnowSoilStateV2.State("world",Map.of());
        var path=directory.resolve("snow.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,
            HydrologySnowSoilStateV2.encode(initial));
        var winter=HydrologySnowSoilStateV2.advance(initial,winter());
        var outlet=at(1,2);
        var graph=List.of(new HydrologyFlowGraphV2.Node(outlet,0,null,true));
        runner.advance(HydrologySnowSoilStateV2.encode(winter.state()),
            List.of(graph));
        var restored=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,restored.epoch());
        var saved=HydrologySnowSoilStateV2.decode(restored.worldState());
        assertEquals(winter.state(),saved);
        var spring=HydrologySnowSoilStateV2.advance(saved,spring());
        assertEquals(HydrologySnowSoilStateV2.advance(winter.state(),spring()).state(),
                     spring.state());
        var values=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var source:spring.runoff())
            values.put(source.address(),source.localWater());
        var flow=List.of(
            new HydrologyFlowGraphV2.Node(POLE,values.get(POLE),at(0,1),false),
            new HydrologyFlowGraphV2.Node(at(0,1),0,EAST,false),
            new HydrologyFlowGraphV2.Node(WEST,values.get(WEST),EAST,false),
            new HydrologyFlowGraphV2.Node(EAST,values.get(EAST),outlet,false),
            new HydrologyFlowGraphV2.Node(outlet,0,null,true));
        var solved=HydrologyFlowGraphV2.solve(flow);
        assertEquals(20,solved.terminalOutflow().get(outlet),1e-10);
        assertEquals(0,HydrologyConservationV2.verify(flow,solved).residual(),1e-10);
        restored.advance(HydrologySnowSoilStateV2.encode(spring.state()),
            List.of(flow));
        var finalCheckpoint=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(spring.state(),
            HydrologySnowSoilStateV2.decode(finalCheckpoint.worldState()));
        assertEquals(2,finalCheckpoint.epoch());
    }

    @Test void codecNestsLakeAndTerrainAndRejectsBadInputs() {
        var lake=HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("world",Map.of()));
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State(lake,Map.of()));
        var state=new HydrologySnowSoilStateV2.State(
            terrain,Map.of(POLE,new HydrologySnowSoilStateV2.Cell(2,1)));
        var encoded=HydrologySnowSoilStateV2.encode(state);
        assertEquals(state,HydrologySnowSoilStateV2.decode(encoded));
        assertEquals(lake,HydrologyTerrainOverlayV2.decode(
            HydrologySnowSoilStateV2.decode(encoded).world()).world());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySnowSoilStateV2.decode(
                encoded.replace("WD-SNOW-SOIL-1","WD-SNOW-SOIL-2")));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySnowSoilStateV2.decode(encoded.replace("0x1.0p1","NaN")));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySnowSoilStateV2.State("world",
                Map.of(at(8,2),new HydrologySnowSoilStateV2.Cell(1,0))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySnowSoilStateV2.advance(state,Map.of(
                POLE,new HydrologySnowSoilStateV2.Forcing(
                    0,1,0,1,1,1,Double.MAX_VALUE),
                EAST,new HydrologySnowSoilStateV2.Forcing(
                    Double.MAX_VALUE,1,0,1,1,1,Double.MAX_VALUE))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySnowSoilStateV2.advance(state,Map.of()));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySnowSoilStateV2.Forcing(1,0,0,1,1,1,0));
    }
}
