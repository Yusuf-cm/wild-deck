package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeEpochV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    private static LakeReservoirV2.Basin basin() {
        return new LakeReservoirV2.Basin(new double[]{0},new double[]{10},2);
    }
    private static HydrologyLakeEpochV2.LakePeriod lake(double rain,double evap) {
        return new HydrologyLakeEpochV2.LakePeriod(at(4),at(5),basin(),10,
            new SeasonalLakeCycleV2.Period(rain,evap,0,1));
    }
    private static List<HydrologyFlowGraphV2.Node> outlet() {
        return List.of(new HydrologyFlowGraphV2.Node(at(5),0,null,true));
    }
    private static String initial() {
        return HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("initial",Map.of()));
    }

    @Test void persistentLakeStorageSpillAndDryingConserveAcrossRestarts()
            throws IOException {
        Path path=directory.resolve("lakes.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        var wet=HydrologyLakeEpochV2.advance(runner,"wet",outlet(),
            List.of(lake(1,0)),8);
        assertEquals(0,wet.routed().committed().flow()
            .terminalOutflow().get(at(5)),1e-9);
        var first=HydrologyLakeStateV2.decode(runner.worldState());
        assertEquals(10,first.volumes().get(at(4)),1e-9);
        assertEquals("wet",first.world());

        var restored=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var spill=HydrologyLakeEpochV2.advance(restored,"overflow",outlet(),
            List.of(lake(2,0)),8);
        assertEquals(10,spill.routed().committed().flow()
            .terminalOutflow().get(at(5)),1e-9);
        assertEquals(10,spill.lakes().get(at(4)).lake().overflow(),1e-9);
        assertEquals(20,HydrologyLakeStateV2.decode(
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState())
            .volumes().get(at(4)),1e-9);
        var third=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var dry=HydrologyLakeEpochV2.advance(third,"dry",outlet(),
            List.of(lake(0,1)),1);
        assertEquals(0,dry.routed().committed().flow()
            .terminalOutflow().get(at(5)),1e-9);
        assertEquals(10,dry.lakes().get(at(4)).lake().evaporated(),1e-9);
        assertEquals(10,HydrologyLakeStateV2.decode(
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState())
            .volumes().get(at(4)),1e-9);
        // 30 units of new inflow = remaining storage + spill + evaporation.
        double stored=HydrologyLakeStateV2.decode(third.worldState())
            .volumes().get(at(4));
        assertEquals(30,stored+spill.lakes().get(at(4)).lake().overflow()+
            dry.lakes().get(at(4)).lake().evaporated(),1e-9);
    }

    @Test void unloadedLakeStateSurvivesEpochWithoutLakeUpdate() throws IOException {
        Path path=directory.resolve("parked-lake.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        HydrologyLakeEpochV2.advance(runner,"wet",outlet(),List.of(lake(1,0)),1);
        HydrologyLakeEpochV2.advance(runner,"region-unloaded",outlet(),List.of(),1);
        assertEquals(10,HydrologyLakeStateV2.decode(
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState())
            .volumes().get(at(4)),1e-9);
    }

    @Test void failedOutletOrChangedBasinDoesNotCommit() throws IOException {
        Path path=directory.resolve("fail-lake.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        HydrologyLakeEpochV2.advance(runner,"wet",outlet(),List.of(lake(1,0)),1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeEpochV2.advance(runner,"invalid",List.of(),
                List.of(lake(1,0)),1));
        var shallower=new LakeReservoirV2.Basin(new double[]{0},new double[]{10},0.5);
        var changed=new HydrologyLakeEpochV2.LakePeriod(at(4),at(5),shallower,10,
            new SeasonalLakeCycleV2.Period(1,0,0,1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeEpochV2.advance(runner,"invalid",outlet(),
                List.of(changed),1));
        assertEquals(1,runner.epoch());
        assertEquals("wet",HydrologyLakeStateV2.decode(
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState()).world());
    }

    @Test void lakeStateCodecIsCanonicalAndRejectsMalformedRecords() {
        var a=at(4);
        var b=at(6);
        var state=new HydrologyLakeStateV2.State("world 🌊",Map.of(b,3.5,a,0.0));
        String encoded=HydrologyLakeStateV2.encode(state);
        assertEquals(state,HydrologyLakeStateV2.decode(encoded));
        assertEquals(encoded,HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("world 🌊",Map.of(a,0.0,b,3.5))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeStateV2.decode(encoded.replace("WD-LAKES-1","BAD")));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeStateV2.decode(encoded+"extra\n"));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyLakeStateV2.State("world",Map.of(a,Double.NaN)));
    }
}
