package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Persistence acceptance: canonical sediment ledger and atomic lake receipts. */
class HydrologySedimentCheckpointV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A=at(0,2),B=at(1,2),C=at(2,2),D=at(3,2);

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(long source,long arrival) {
        return new HydrologySedimentReceiptInboxV2.Receipt(source,arrival,A,B);
    }

    private static HydrologySphericalLakeBasinsV2.Plan baseline() {
        var routes=new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,D,false),
            new HydrologyFlowGraphV2.Node(D,0,null,true)));
        for (long y=0;y<=4;y++)
            for (long x=0;x<(y==0||y==4?1:8);x++) {
                var address=at(x,y);
                if (!Set.of(A,B,C,D).contains(address))
                    routes.add(new HydrologyFlowGraphV2.Node(address,0,null,true));
            }
        return new HydrologySphericalLakeBasinsV2.Plan(routes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,D,
                new LakeReservoirV2.Basin(new double[]{0},new double[]{1},5))));
    }

    private HydrologyCheckpointV2.Checkpoint start(Path path,double water) throws IOException {
        var base=baseline();
        String world=HydrologyLakeSedimentSnapshotV2.encode(base,base,
            new HydrologyLakeStateV2.State("world",Map.of(B,water)));
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(world),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        return checkpoint;
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> capacity() {
        var map=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:baseline().routes()) map.put(node.address(),10000.0);
        return map;
    }

    private static HydrologySedimentCheckpointV2.Committed advance(
            Path path,HydrologyCheckpointV2.Checkpoint previous) throws IOException {
        return HydrologySedimentCheckpointV2.advanceLake(path,previous,baseline(),
            Map.of(B,new HydrologyLakeNetworkStepV2.Forcing(0,0,1)),
            capacity(),2000,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
    }

    @Test void codecIsCanonicalAndRestoresDelayedAndDeliveredReceipts() {
        var a=receipt(0,2);
        var d=new HydrologySedimentReceiptInboxV2.Receipt(1,3,at(1,3),B);
        var left=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.receive(
                HydrologySedimentReceiptInboxV2.empty(),a,3),d,5);
        var right=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.receive(
                HydrologySedimentReceiptInboxV2.empty(),d,5),a,3);
        assertEquals(HydrologySedimentReceiptCodecV2.encode(left),
                     HydrologySedimentReceiptCodecV2.encode(right));
        var encoded=HydrologySedimentReceiptCodecV2.encode(left);
        assertEquals(left,HydrologySedimentReceiptCodecV2.decode(encoded));
        var epoch2=HydrologySedimentReceiptInboxV2.claimAvailable(left,2,List.of(B));
        assertEquals(3,epoch2.importedMass(),1e-12);
        var restarted=HydrologySedimentReceiptCodecV2.decode(
            HydrologySedimentReceiptCodecV2.encode(epoch2.snapshot()));
        assertEquals(5,HydrologySedimentReceiptInboxV2.claimAvailable(
            restarted,3,List.of(B)).importedMass(),1e-12);
        assertSame(restarted,HydrologySedimentReceiptInboxV2.receive(restarted,a,3));
    }

    @Test void codecAndEnvelopeRejectCorruptionAndNoncanonicalRecords() {
        String ledger=HydrologySedimentReceiptCodecV2.encode(
            HydrologySedimentReceiptInboxV2.receive(
                HydrologySedimentReceiptInboxV2.empty(),receipt(0,2),2));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptCodecV2.decode(ledger.replace("0x1.0p1","0x1.8p1")));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptCodecV2.decode(ledger+"extra"));
        String envelope=HydrologySedimentCheckpointV2.encode("hello\nworld",
            HydrologySedimentReceiptInboxV2.empty());
        assertEquals("hello\nworld",HydrologySedimentCheckpointV2.decode(envelope).world());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCheckpointV2.decode(envelope.replace("aGVsbG8","bGVsbG8")));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCheckpointV2.decode("not-an-envelope"));
    }

    @Test void futureReceiptIsDurableAndSameEpochReplaysAreIdempotent() throws IOException {
        Path path=directory.resolve("receiving.chk");
        var initial=start(path,3);
        var first=HydrologySedimentCheckpointV2.receive(path,initial,receipt(0,1),2000);
        assertEquals(first,HydrologyCheckpointStoreV2.load(path));
        assertEquals(first,HydrologySedimentCheckpointV2.receive(path,first,receipt(0,1),2000));
        assertThrows(IOException.class,()->
            HydrologySedimentCheckpointV2.receive(path,initial,receipt(0,2),2000));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCheckpointV2.receive(path,first,receipt(0,1),3000));
        assertEquals(first,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void sedimentLakeGeometryAndDeliverySurviveRestartWithoutReplay() throws IOException {
        Path path=directory.resolve("lake.chk");
        var previous=start(path,3);
        previous=HydrologySedimentCheckpointV2.receive(path,previous,receipt(0,1),2000);
        var first=advance(path,previous);
        assertEquals(1,first.checkpoint().epoch());
        assertEquals(2000,first.result().imported(),1e-12);
        assertTrue(first.sediment().delivered().contains(receipt(0,1)));
        var loaded=HydrologyCheckpointStoreV2.load(path);
        var state=HydrologySedimentCheckpointV2.decode(loaded.worldState());
        assertEquals(first.sediment(),state.sediment());
        var restored=HydrologyLakeSedimentSnapshotV2.decode(baseline(),state.world());
        assertEquals(4,restored.plan().lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(3,restored.water().volumes().get(B),1e-12);
        var second=advance(path,loaded);
        assertEquals(0,second.result().imported(),1e-12);
        assertEquals(4,HydrologyLakeSedimentSnapshotV2.decode(baseline(),
            HydrologySedimentCheckpointV2.decode(
                HydrologyCheckpointStoreV2.load(path).worldState()).world())
            .plan().lakes().get(0).reservoir().capacity(),1e-12);
    }

    @Test void completeSiltingFailsWithoutConsumingReceiptOrEpoch() throws IOException {
        Path path=directory.resolve("silt.chk");
        var initial=start(path,3);
        var pending=HydrologySedimentCheckpointV2.receive(path,initial,receipt(0,1),10000);
        assertThrows(IllegalArgumentException.class,()->advance(path,pending));
        assertEquals(pending,HydrologyCheckpointStoreV2.load(path));
        assertTrue(HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(path).worldState()).sediment().delivered().isEmpty());
    }

    @Test void pendingWaterInboxIsNotSilentlySkipped() throws IOException {
        Path path=directory.resolve("water.chk");
        var base=baseline();
        String world=HydrologyLakeSedimentSnapshotV2.encode(base,base,
            new HydrologyLakeStateV2.State("world",Map.of(B,0.0)));
        var water=new HydrologyEpochInboxV2();
        water.receive(new HydrologyTransferLedgerV2.Key(1,A,B),2);
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(world),water);
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        assertThrows(IllegalArgumentException.class,()->advance(path,checkpoint));
        assertEquals(checkpoint,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void staleEpochWriterCannotOverwriteNewSedimentAndLakeState() throws IOException {
        Path path=directory.resolve("stale.chk");
        var old=start(path,3);
        var updated=HydrologySedimentCheckpointV2.receive(path,old,receipt(0,1),2000);
        advance(path,updated);
        assertThrows(IOException.class,()->advance(path,updated));
        assertEquals(1,HydrologyCheckpointStoreV2.load(path).epoch());
    }
    @Test void sameEpochStoreRejectsWorldRewriteAndClosedArrival() throws IOException {
        Path path=directory.resolve("forgery.chk");
        var expected=start(path,1);
        var original=HydrologySedimentCheckpointV2.decode(expected.worldState());
        var rewritten=new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            HydrologySedimentCheckpointV2.encode("forged world",original.sediment()),
            expected.inboxState());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyCheckpointStoreV2.saveSedimentIncoming(path,expected,rewritten));
        var oldReceipt=new HydrologySedimentReceiptInboxV2.Receipt(0,0,A,B);
        var withPast=HydrologySedimentReceiptInboxV2.receive(
            original.sediment(),oldReceipt,1);
        var invalid=new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            HydrologySedimentCheckpointV2.encode(original.world(),withPast),
            expected.inboxState());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyCheckpointStoreV2.saveSedimentIncoming(path,expected,invalid));
        assertEquals(expected,HydrologyCheckpointStoreV2.load(path));
    }


    private HydrologyCheckpointV2.Checkpoint startTerrain(Path path,double volume,
            Map<GlobalHydrologyAddressV2.Sample,Double> overrides) throws IOException {
        var base = baseline();
        var terrain = HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("initial",overrides));
        var lake = HydrologyLakeSedimentSnapshotV2.encode(base,base,
            new HydrologyLakeStateV2.State(terrain,Map.of(B,volume)));
        var checkpoint = HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(lake),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        return checkpoint;
    }

    private static HydrologySedimentCheckpointV2.Committed advanceTerrain(
            Path path,HydrologyCheckpointV2.Checkpoint previous,String world)
            throws IOException {
        return HydrologySedimentCheckpointV2.advanceLakeWithTerrain(
            path,previous,baseline(),world,address -> 0.0,
            Map.of(B,new HydrologyLakeNetworkStepV2.Forcing(0,0,1)),
            capacity(),2000,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
    }

    @Test void importedSedimentLakeTerrainAndReceiptCommitInOneCheckpoint()
            throws IOException {
        Path path=directory.resolve("terrain-coupled.chk");
        var unrelated=at(7,3);
        var first=startTerrain(path,3,Map.of(unrelated,7.0));
        var pending=HydrologySedimentCheckpointV2.receive(
            path,first,receipt(0,1),2000);
        var committed=advanceTerrain(path,pending,"season-one");
        assertEquals(1,committed.checkpoint().epoch());
        assertTrue(committed.sediment().delivered().contains(receipt(0,1)));
        var persisted=HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(path).worldState());
        var lake=HydrologyLakeSedimentSnapshotV2.decode(baseline(),persisted.world());
        var terrain=HydrologyTerrainOverlayV2.decode(lake.water().world());
        assertEquals("season-one",terrain.world());
        assertEquals(1.0,terrain.elevations().get(B),0.0);
        assertEquals(7.0,terrain.elevations().get(unrelated),0.0);
        assertEquals(4.0,lake.plan().lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(3.0,lake.water().volumes().get(B),1e-12);
        assertEquals(2000,committed.result().imported(),1e-12);
        var second=advanceTerrain(path,committed.checkpoint(),"season-two");
        var next=HydrologyLakeSedimentSnapshotV2.decode(baseline(),
            HydrologySedimentCheckpointV2.decode(second.checkpoint().worldState()).world());
        assertEquals(1.0,HydrologyTerrainOverlayV2.decode(next.water().world())
            .elevations().get(B),0.0);
        assertEquals(0,second.result().imported(),0.0);
        assertEquals(4.0,next.plan().lakes().get(0).reservoir().capacity(),1e-12);
    }

    @Test void lakeTerrainMismatchRejectsWithoutConsumingSediment() throws IOException {
        Path path=directory.resolve("terrain-mismatch.chk");
        var initial=startTerrain(path,3,Map.of(B,2.0));
        var pending=HydrologySedimentCheckpointV2.receive(
            path,initial,receipt(0,1),2000);
        assertThrows(IllegalArgumentException.class,()->
            advanceTerrain(path,pending,"should-not-commit"));
        assertEquals(pending,HydrologyCheckpointStoreV2.load(path));
        assertFalse(HydrologySedimentCheckpointV2.decode(pending.worldState())
            .sediment().delivered().contains(receipt(0,1)));
    }

    @Test void terrainModeRejectsSiltingAndStaleCheckpointWithoutPartialWrites()
            throws IOException {
        Path full=directory.resolve("terrain-full.chk");
        var initial=startTerrain(full,3,Map.of());
        var pending=HydrologySedimentCheckpointV2.receive(
            full,initial,receipt(0,1),10000);
        assertThrows(IllegalArgumentException.class,()->
            advanceTerrain(full,pending,"silting"));
        assertEquals(pending,HydrologyCheckpointStoreV2.load(full));

        Path stale=directory.resolve("terrain-stale.chk");
        var original=startTerrain(stale,3,Map.of());
        var received=HydrologySedimentCheckpointV2.receive(
            stale,original,receipt(0,1),2000);
        var committed=advanceTerrain(stale,received,"season-one");
        assertThrows(IOException.class,()->advanceTerrain(stale,received,"stale"));
        assertEquals(committed.checkpoint(),HydrologyCheckpointStoreV2.load(stale));
    }

    @Test void terrainModeRequiresCanonicalOverlayAndPreservesWaterInbox()
            throws IOException {
        Path path=directory.resolve("terrain-invalid.chk");
        var previous=start(path,3);
        assertThrows(IllegalArgumentException.class,()->
            advanceTerrain(path,previous,"invalid"));
        assertEquals(previous,HydrologyCheckpointStoreV2.load(path));

        Path waterPath=directory.resolve("terrain-water.chk");
        var base=baseline();
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("world",Map.of()));
        var snapshot=HydrologyLakeSedimentSnapshotV2.encode(base,base,
            new HydrologyLakeStateV2.State(terrain,Map.of(B,3.0)));
        var water=new HydrologyEpochInboxV2();
        water.receive(new HydrologyTransferLedgerV2.Key(1,A,B),2);
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(snapshot),water);
        HydrologyCheckpointStoreV2.save(waterPath,checkpoint);
        assertThrows(IllegalArgumentException.class,()->
            advanceTerrain(waterPath,checkpoint,"invalid-water"));
        assertEquals(checkpoint,HydrologyCheckpointStoreV2.load(waterPath));
    }

}
