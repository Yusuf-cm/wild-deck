package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverbedCheckpointV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(1,2), B=at(2,2), C=at(3,2);
    private static HydrologySphericalLakeBasinsV2.Plan baseline() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return id -> id.equals(A)?15:id.equals(B)?10:5;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> eroded() {
        return Map.of(A,1000.0,B,0.0,C,0.0);
    }
    private static HydrologyRiverSedimentTransportV2.Result transport() {
        return HydrologyRiverSedimentTransportV2.route(baseline(),eroded(),
            Map.of(A,1000.0,B,0.0,C,0.0));
    }
    private HydrologyCheckpointV2.Checkpoint start(Path path,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Double> edits,
            Map<GlobalHydrologyAddressV2.Sample,Double> water,
            HydrologyEpochInboxV2 inbox) throws IOException {
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("before",edits));
        var lakes=HydrologyLakeSedimentSnapshotV2.encode(plan,plan,
            new HydrologyLakeStateV2.State(terrain,water));
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(lakes),inbox);
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        return checkpoint;
    }
    private HydrologyCheckpointV2.Checkpoint start(Path path) throws IOException {
        return start(path,baseline(),Map.of(at(7,3),42.0),Map.of(),
                     new HydrologyEpochInboxV2());
    }
    private static HydrologyRiverbedCheckpointV2.Planned commit(Path path,
            HydrologyCheckpointV2.Checkpoint previous) throws IOException {
        return HydrologyRiverbedCheckpointV2.commit(
            path,previous,baseline(),"after",seed(),eroded(),transport(),
            Map.of(A,1.0,B,1.0),1000,2,2);
    }

    @Test void atomicCheckpointPersistsBothRiverbedChangesAndSedimentEpoch()
            throws IOException {
        Path path=directory.resolve("river.chk");
        var first=start(path);
        var planned=HydrologyRiverbedCheckpointV2.plan(
            first,baseline(),"after",seed(),eroded(),transport(),
            Map.of(A,1.0,B,1.0),1000,2,2);
        assertEquals(first,HydrologyCheckpointStoreV2.load(path));
        var committed=commit(path,first);
        assertEquals(planned.next(),committed.next());
        assertEquals(committed.next(),HydrologyCheckpointStoreV2.load(path));
        assertEquals(1,committed.next().epoch());
        assertEquals(first.inboxState(),committed.next().inboxState());
        var envelope=HydrologySedimentCheckpointV2.decode(committed.next().worldState());
        assertEquals(1,envelope.sediment().lastClaimedEpoch());
        var restored=HydrologyLakeSedimentSnapshotV2.decode(baseline(),envelope.world());
        var terrain=HydrologyTerrainOverlayV2.decode(restored.water().world());
        assertEquals("after",terrain.world());
        assertEquals(14.0,terrain.elevations().get(A),0.0);
        assertEquals(11.0,terrain.elevations().get(B),0.0);
        assertEquals(42.0,terrain.elevations().get(at(7,3)),0.0);
    }

    @Test void restartAndNextEpochDoNotReapplyEarlierErosion() throws IOException {
        Path path=directory.resolve("restart.chk");
        var first=commit(path,start(path));
        var loaded=HydrologyCheckpointStoreV2.load(path);
        var zeros=Map.of(A,0.0,B,0.0,C,0.0);
        var routed=HydrologyRiverSedimentTransportV2.route(baseline(),zeros,zeros);
        var second=HydrologyRiverbedCheckpointV2.commit(
            path,loaded,baseline(),"season-two",seed(),zeros,routed,
            Map.of(),1000,2,2);
        assertEquals(2,second.next().epoch());
        assertEquals(0,second.riverbed().changedVertices());
        var restored=HydrologyLakeSedimentSnapshotV2.decode(baseline(),
            HydrologySedimentCheckpointV2.decode(second.next().worldState()).world());
        var terrain=HydrologyTerrainOverlayV2.decode(restored.water().world());
        assertEquals(14.0,terrain.elevations().get(A),0.0);
        assertEquals(11.0,terrain.elevations().get(B),0.0);
        assertEquals("season-two",terrain.world());
        assertThrows(IOException.class,()->commit(path,loaded));
        assertEquals(second.next(),HydrologyCheckpointStoreV2.load(path));
    }

    @Test void pendingWaterAndSedimentCannotBeSkipped() throws IOException {
        Path waterPath=directory.resolve("pending-water.chk");
        var water=new HydrologyEpochInboxV2();
        water.receive(new HydrologyTransferLedgerV2.Key(1,A,B),3);
        var pendingWater=start(waterPath,baseline(),Map.of(),Map.of(),water);
        assertThrows(IllegalArgumentException.class,()->commit(waterPath,pendingWater));
        assertEquals(pendingWater,HydrologyCheckpointStoreV2.load(waterPath));

        Path sedimentPath=directory.resolve("pending-sediment.chk");
        var before=start(sedimentPath);
        var receipt=new HydrologySedimentReceiptInboxV2.Receipt(0,1,C,B);
        var pending=HydrologySedimentCheckpointV2.receive(
            sedimentPath,before,receipt,2);
        assertThrows(IllegalArgumentException.class,()->commit(sedimentPath,pending));
        assertEquals(pending,HydrologyCheckpointStoreV2.load(sedimentPath));
    }

    @Test void preservesLakeWaterAndGeometryWithoutEditingLakeBed() throws IOException {
        Path path=directory.resolve("lake.chk");
        var lake=new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,C,
            new LakeReservoirV2.Basin(new double[]{10},new double[]{1},12));
        var withLake=new HydrologySphericalLakeBasinsV2.Plan(
            baseline().routes(),List.of(lake));
        var first=start(path,withLake,Map.of(),Map.of(B,1.0),
            new HydrologyEpochInboxV2());
        var zeros=Map.of(A,0.0,B,0.0,C,0.0);
        var routed=HydrologyRiverSedimentTransportV2.route(withLake,zeros,zeros);
        var next=HydrologyRiverbedCheckpointV2.commit(
            path,first,withLake,"next",seed(),zeros,routed,Map.of(),
            1000,2,2);
        var restored=HydrologyLakeSedimentSnapshotV2.decode(withLake,
            HydrologySedimentCheckpointV2.decode(next.next().worldState()).world());
        assertEquals(1.0,restored.water().volumes().get(B),0.0);
        assertArrayEquals(new double[]{10},restored.plan().lakes().get(0)
            .reservoir().bedElevation(),0.0);
    }

    @Test void lakeBedMismatchAndInvalidBudgetRollBackEntireCheckpoint()
            throws IOException {
        Path path=directory.resolve("mismatch.chk");
        var lake=new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,C,
            new LakeReservoirV2.Basin(new double[]{10},new double[]{1},12));
        var withLake=new HydrologySphericalLakeBasinsV2.Plan(
            baseline().routes(),List.of(lake));
        var old=start(path,withLake,Map.of(B,9.0),Map.of(B,1.0),
            new HydrologyEpochInboxV2());
        var zeros=Map.of(A,0.0,B,0.0,C,0.0);
        var routed=HydrologyRiverSedimentTransportV2.route(withLake,zeros,zeros);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedCheckpointV2.commit(path,old,withLake,"next",
                seed(),zeros,routed,Map.of(),1000,2,2));
        assertEquals(old,HydrologyCheckpointStoreV2.load(path));

        Path exported=directory.resolve("export.chk");
        var first=start(exported);
        var outgoing=HydrologyRiverSedimentTransportV2.route(
            baseline(),eroded(),Map.of(A,1000.0,B,1000.0,C,1000.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedCheckpointV2.commit(exported,first,baseline(),
                "next",seed(),eroded(),outgoing,Map.of(A,1.0,B,1.0),1000,2,2));
        assertEquals(first,HydrologyCheckpointStoreV2.load(exported));
    }
}
