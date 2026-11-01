package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverbedRouteVerifiedCheckpointV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(3,3), B=at(4,3), C=at(5,3);
    private static final GlobalHydrologyAddressV2.Sample HALO=at(3,2);

    private static HydrologySphericalLakeBasinsV2.Plan drainage() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return at -> at.equals(A)?30:at.equals(B)?20:at.equals(C)?10:100;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> eroded() {
        return Map.of(A,1000.0,B,0.0,C,0.0);
    }
    private static HydrologyRiverSedimentTransportV2.Result transport() {
        return HydrologyRiverSedimentTransportV2.route(drainage(),eroded(),
            Map.of(A,1000.0,B,0.0,C,0.0));
    }
    private HydrologyCheckpointV2.Checkpoint start(Path path) throws IOException {
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("before",Map.of()));
        var world=HydrologyLakeSedimentSnapshotV2.encode(
            drainage(),drainage(),new HydrologyLakeStateV2.State(terrain,Map.of()));
        var initial=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(world),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,initial);
        return initial;
    }
    private static HydrologyRiverbedCheckpointV2.Planned commit(
            Path path,HydrologyCheckpointV2.Checkpoint previous,
            HydrologyGlobalSlopeRouterV2.Elevations elevations) throws IOException {
        return HydrologyRiverbedCheckpointV2.commitRouteVerified(
            path,previous,drainage(),"after",elevations,eroded(),transport(),
            Map.of(A,1.0,B,1.0),1000,2,2);
    }

    @Test void routeVerifiedMassConservingRiverbedEpochSurvivesRestart()
            throws IOException {
        var path=directory.resolve("verified.chk");
        var initial=start(path);
        var result=commit(path,initial,seed());
        assertEquals(1,result.next().epoch());
        assertEquals(2,result.riverbed().changedVertices());
        assertEquals(result.next(),HydrologyCheckpointStoreV2.load(path));
        var envelope=HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(path).worldState());
        assertEquals(1,envelope.sediment().lastClaimedEpoch());
        var restored=HydrologyLakeSedimentSnapshotV2.decode(drainage(),envelope.world());
        var terrain=HydrologyTerrainOverlayV2.decode(restored.water().world());
        assertEquals(29.0,terrain.elevations().get(A),0.0);
        assertEquals(21.0,terrain.elevations().get(B),0.0);
        assertEquals("after",terrain.world());
    }

    @Test void competingUnloadedHaloRouteRejectsBeforeCheckpointWrite()
            throws IOException {
        var path=directory.resolve("halo.chk");
        var initial=start(path);
        var halo=(HydrologyGlobalSlopeRouterV2.Elevations) at ->
            at.equals(HALO)?60:seed().at(at);
        // Ordinary closed-budget preview accepts both existing directed edges.
        assertEquals(2,HydrologyRiverbedCheckpointV2.plan(
            initial,drainage(),"after",halo,eroded(),transport(),
            Map.of(A,1.0,B,1.0),1000,2,2).riverbed().changedVertices());
        assertThrows(IllegalArgumentException.class,()->commit(path,initial,halo));
        assertEquals(initial,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void routeVerifiedCommitRejectsStaleWriterWithoutReapplyingSediment()
            throws IOException {
        var path=directory.resolve("stale.chk");
        var initial=start(path);
        var committed=commit(path,initial,seed());
        assertThrows(IOException.class,()->commit(path,initial,seed()));
        assertEquals(committed.next(),HydrologyCheckpointStoreV2.load(path));
    }
}
