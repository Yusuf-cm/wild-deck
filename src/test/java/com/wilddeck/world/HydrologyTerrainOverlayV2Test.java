package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTerrainOverlayV2Test {
    @TempDir Path directory;

    private static List<GlobalHydrologyAddressV2.Sample> addresses() {
        var result=new ArrayList<GlobalHydrologyAddressV2.Sample>();
        for(int y=4;y<9;y++)for(int x=4;x<9;x++)
            result.add(new GlobalHydrologyAddressV2.Sample(2,4,x,y));
        return List.copyOf(result);
    }

    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return address->20-(address.x()-4)-(address.y()-4);
    }

    private static HydrologyTerrainOverlayV2.Evolved evolve(
            HydrologyTerrainOverlayV2.State state) {
        double[] rain=new double[25],area=new double[25],capacity=new double[25];
        Arrays.fill(rain,2);
        Arrays.fill(area,1);
        Arrays.fill(capacity,2);
        return HydrologyTerrainOverlayV2.evolveWindow(
            state,addresses(),5,5,seed(),rain,area,2000,.1,.4,
            capacity,Set.of(24));
    }

    @Test void sedimentChangesSurviveCheckpointRestartAndContinueEvolving()
            throws IOException {
        var initial=new HydrologyTerrainOverlayV2.State("original",Map.of());
        var first=evolve(initial);
        assertTrue(first.landscape().erodedMass()>0);
        assertTrue(first.landscape().depositedMass()>0);
        assertEquals(first.landscape().erodedMass(),
            first.landscape().depositedMass()+first.landscape().exportedMass(),1e-7);
        assertEquals(50,first.landscape().discharge().flow()[24],1e-8);
        assertEquals(25,first.state().elevations().size());
        assertTrue(first.state().elevations().entrySet().stream().anyMatch(
            e->e.getValue()!=seed().at(e.getKey())));
        assertTrue(initial.elevations().isEmpty());
        var path=directory.resolve("terrain.chk");
        var runner=HydrologyWorldRunnerV2.open(
            path,0,HydrologyTerrainOverlayV2.encode(initial));
        var outlet=new GlobalHydrologyAddressV2.Sample(2,4,4,4);
        runner.advance(HydrologyTerrainOverlayV2.encode(first.state()),
            List.of(List.of(new HydrologyFlowGraphV2.Node(outlet,0,null,true))));
        var restored=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,restored.epoch());
        var recovered=HydrologyTerrainOverlayV2.decode(restored.worldState());
        assertEquals(first.state(),recovered);
        var second=evolve(recovered);
        assertEquals(second.state(),evolve(first.state()).state());
        assertEquals(25,second.state().elevations().size());
        assertTrue(second.state().elevations().entrySet().stream().anyMatch(
            e->!Objects.equals(e.getValue(),first.state().elevations().get(e.getKey()))));
        assertEquals(20,seed().at(addresses().get(0)),0);
    }

    @Test void codecIsCanonicalAndCanNestLakeState() {
        var lake=HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("world",Map.of()));
        var id=addresses().get(0);
        var state=new HydrologyTerrainOverlayV2.State(lake,Map.of(id,12.5));
        var encoded=HydrologyTerrainOverlayV2.encode(state);
        var decoded=HydrologyTerrainOverlayV2.decode(encoded);
        assertEquals(state,decoded);
        assertEquals("world",HydrologyLakeStateV2.decode(decoded.world()).world());
        assertEquals(12.5,HydrologyTerrainOverlayV2.elevations(seed(),decoded)
            .at(id),0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTerrainOverlayV2.decode(
                encoded.replace("WD-TERRAIN-1","WD-TERRAIN-2")));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTerrainOverlayV2.State("x",Map.of(id,Double.NaN)));
    }

    @Test void rejectsNoncontiguousWindowBeforeErosion() {
        var broken=new ArrayList<>(addresses());
        Collections.swap(broken,0,1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTerrainOverlayV2.evolveWindow(
                new HydrologyTerrainOverlayV2.State("x",Map.of()),
                broken,5,5,seed(),new double[25],new double[25],
                1,0,0,new double[25],Set.of(24)));
    }
}
