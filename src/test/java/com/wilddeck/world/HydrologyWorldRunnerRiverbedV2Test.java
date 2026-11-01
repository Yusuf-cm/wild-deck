package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runner-level persistence and rollback checks for closed-budget riverbed epochs. */
class HydrologyWorldRunnerRiverbedV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,2,x,y);
    }

    private static final GlobalHydrologyAddressV2.Sample A=at(3,3);
    private static final GlobalHydrologyAddressV2.Sample B=at(4,3);
    private static final GlobalHydrologyAddressV2.Sample C=at(5,3);
    private static final GlobalHydrologyAddressV2.Sample HALO=at(3,2);

    private static HydrologySphericalLakeBasinsV2.Plan drainage() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
    }

    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return sample -> sample.equals(A)?30:sample.equals(B)?20:
            sample.equals(C)?10:100;
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> eroded() {
        return Map.of(A,1000.0,B,0.0,C,0.0);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> zeros() {
        return Map.of(A,0.0,B,0.0,C,0.0);
    }

    private static HydrologyRiverSedimentTransportV2.Result transport(
            Map<GlobalHydrologyAddressV2.Sample,Double> source) {
        return HydrologyRiverSedimentTransportV2.route(drainage(),source,
            source.equals(zeros())?zeros():Map.of(A,1000.0,B,0.0,C,0.0));
    }

    private HydrologyCheckpointV2.Checkpoint initial(Path path) throws IOException {
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("before",Map.of()));
        var lakes=HydrologyLakeSedimentSnapshotV2.encode(
            drainage(),drainage(),new HydrologyLakeStateV2.State(terrain,Map.of()));
        var first=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(lakes),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,first);
        return first;
    }

    private static HydrologyRiverbedCheckpointV2.Planned preview(
            HydrologyWorldRunnerV2 runner,
            HydrologyGlobalSlopeRouterV2.Elevations heights) throws IOException {
        return runner.planRouteVerifiedRiverbedEpoch(
            drainage(),"after",heights,eroded(),transport(eroded()),
            Map.of(A,1.0,B,1.0),1000,2,2);
    }

    private static HydrologyRiverbedCheckpointV2.Planned advance(
            HydrologyWorldRunnerV2 runner,
            HydrologyGlobalSlopeRouterV2.Elevations heights) throws IOException {
        return runner.advanceRouteVerifiedRiverbedEpoch(
            drainage(),"after",heights,eroded(),transport(eroded()),
            Map.of(A,1.0,B,1.0),1000,2,2);
    }

    @Test void previewIsReadOnlyAndRunnerPersistsRiverbedWithSedimentEpoch()
            throws IOException {
        var path=directory.resolve("riverbed.chk");
        var first=initial(path);
        var runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var planned=preview(runner,seed());
        assertEquals(0,runner.epoch());
        assertEquals(first,HydrologyCheckpointStoreV2.load(path));
        var committed=advance(runner,seed());
        assertEquals(planned.next(),committed.next());
        assertEquals(1,runner.epoch());
        assertEquals(committed.next(),HydrologyCheckpointStoreV2.load(path));
        var restarted=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,restarted.epoch());
        assertEquals(committed.next().worldState(),restarted.worldState());
        var sediment=HydrologySedimentCheckpointV2.decode(restarted.worldState());
        assertEquals(1,sediment.sediment().lastClaimedEpoch());
        var lake=HydrologyLakeSedimentSnapshotV2.decode(drainage(),sediment.world());
        var terrain=HydrologyTerrainOverlayV2.decode(lake.water().world());
        assertEquals(29.0,terrain.elevations().get(A),0.0);
        assertEquals(21.0,terrain.elevations().get(B),0.0);
        assertEquals("after",terrain.world());
    }

    @Test void restartCanAdvanceAnotherClosedEpochWithoutReplayingErosion()
            throws IOException {
        var path=directory.resolve("second.chk");
        initial(path);
        advance(HydrologyWorldRunnerV2.open(path,0,"ignored"),seed());
        var restarted=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var zero= zeros();
        var second=restarted.advanceRouteVerifiedRiverbedEpoch(
            drainage(),"second",seed(),zero,transport(zero),Map.of(),
            1000,2,2);
        assertEquals(2,restarted.epoch());
        assertEquals(0,second.riverbed().changedVertices());
        var lake=HydrologyLakeSedimentSnapshotV2.decode(drainage(),
            HydrologySedimentCheckpointV2.decode(second.next().worldState()).world());
        var terrain=HydrologyTerrainOverlayV2.decode(lake.water().world());
        assertEquals(29.0,terrain.elevations().get(A),0.0);
        assertEquals(21.0,terrain.elevations().get(B),0.0);
        assertEquals("second",terrain.world());
    }

    @Test void reroutingUnloadedHaloRejectsBeforeRunnerMutation()
            throws IOException {
        var path=directory.resolve("halo.chk");
        var first=initial(path);
        var runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        HydrologyGlobalSlopeRouterV2.Elevations unsafe = sample ->
            sample.equals(HALO)?60:seed().at(sample);
        assertThrows(IllegalArgumentException.class,()->preview(runner,unsafe));
        assertThrows(IllegalArgumentException.class,()->advance(runner,unsafe));
        assertEquals(0,runner.epoch());
        assertEquals(first,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void pendingWaterCannotBeSkippedByRiverbedRunner() throws IOException {
        var path=directory.resolve("water.chk");
        initial(path);
        var runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var key=new HydrologyTransferLedgerV2.Key(1,A,B);
        assertTrue(runner.receive(key,3));
        var pending=HydrologyCheckpointStoreV2.load(path);
        assertThrows(IllegalArgumentException.class,()->advance(runner,seed()));
        assertEquals(pending,HydrologyCheckpointStoreV2.load(path));
        assertEquals(0,runner.epoch());
        assertEquals(1,runner.inboxSnapshot().receipts().size());
    }

    @Test void pendingSedimentCannotBeSkippedByRiverbedRunner() throws IOException {
        var path=directory.resolve("sediment.chk");
        var first=initial(path);
        var receipt=new HydrologySedimentReceiptInboxV2.Receipt(0,1,C,B);
        var pending=HydrologySedimentCheckpointV2.receive(path,first,receipt,2);
        var runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertThrows(IllegalArgumentException.class,()->advance(runner,seed()));
        assertEquals(pending,HydrologyCheckpointStoreV2.load(path));
        assertEquals(0,runner.epoch());
    }

    @Test void staleRunnerRejectsPreviewAndCommitWithoutOverwriting()
            throws IOException {
        var path=directory.resolve("stale.chk");
        initial(path);
        var current=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var stale=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var committed=advance(current,seed());
        assertThrows(IOException.class,()->preview(stale,seed()));
        assertThrows(IOException.class,()->advance(stale,seed()));
        assertEquals(0,stale.epoch());
        assertEquals(committed.next(),HydrologyCheckpointStoreV2.load(path));
    }
}
