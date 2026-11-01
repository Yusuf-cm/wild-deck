package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full checkpoint integration: geometry, water and receipt delivery together. */
class HydrologyLakeSedimentAtomicEpochV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        FROM=at(0,2), A=at(1,2), B=at(2,2), C=at(3,2), D=at(4,2);

    private static HydrologySphericalLakeBasinsV2.Plan baseline() {
        var routes = new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(FROM,0,A,false),
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,D,false),
            new HydrologyFlowGraphV2.Node(D,0,null,true)));
        for (long y=0;y<=4;y++)
            for (long x=0;x<(y==0||y==4?1:8);x++) {
                var address=at(x,y);
                if (!Set.of(FROM,A,B,C,D).contains(address))
                    routes.add(new HydrologyFlowGraphV2.Node(address,0,null,true));
            }
        return new HydrologySphericalLakeBasinsV2.Plan(routes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A,List.of(A),B,D,
                new LakeReservoirV2.Basin(
                    new double[]{0},new double[]{1},5))));
    }
    private static HydrologyWorldRunnerV2 open(Path path,double initial)
            throws IOException {
        var base=baseline();
        var state=new HydrologyLakeStateV2.State(
            "initial",initial==0?Map.of():Map.of(A,initial));
        return HydrologyWorldRunnerV2.open(path,0,
            HydrologyLakeSedimentSnapshotV2.encode(base,base,state));
    }
    private static List<HydrologyFlowGraphV2.Node> runoff(double amount) {
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for (var node:baseline().routes())
            nodes.add(new HydrologyFlowGraphV2.Node(
                node.address(),node.address().equals(A)?amount:0,
                node.downstream(),node.terminal()));
        return nodes;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
            HydrologyLakeNetworkStepV2.Forcing> water() {
        return Map.of(A,new HydrologyLakeNetworkStepV2.Forcing(0,0,1));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
            HydrologyLakeSedimentNetworkV2.Sediment> sediment(double mass) {
        return Map.of(A,new HydrologyLakeSedimentNetworkV2.Sediment(mass,2000));
    }
    private static HydrologyLakeSedimentNetworkV2.Result advance(
            HydrologyWorldRunnerV2 runner,double runoff,double sediment)
            throws IOException {
        return runner.advanceClosedSphericalLakeSedimentNetwork(
            "season-"+(runner.epoch()+1),baseline(),runoff(runoff),
            water(),sediment(sediment),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
    }

    @Test void incomingReceiptAndDisplacedWaterCommitOnceWithGeometry() throws IOException {
        var path=directory.resolve("coupled.chk");
        var runner=open(path,4);
        var receipt=new HydrologyTransferLedgerV2.Key(1,FROM,A);
        assertTrue(runner.receive(receipt,2));
        assertFalse(runner.receive(receipt,2));
        var first=advance(runner,1,4000);
        assertEquals(1,first.displaced().get(A),1e-12);
        assertEquals(3,first.updatedPlan().lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(3,first.water().state().volumes().get(A),1e-12);
        assertEquals(4,first.budget().terminalOutflow(),1e-12);
        assertEquals(3,first.budget().sourceWater(),1e-12);
        assertEquals(0,first.budget().waterResidual(),1e-12);
        assertTrue(runner.inboxSnapshot().delivered().contains(receipt));

        var reopened=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,reopened.epoch());
        var restored=HydrologyLakeSedimentSnapshotV2.decode(
            baseline(),reopened.worldState());
        assertEquals("season-1",restored.water().world());
        assertEquals(3,restored.plan().lakes().get(0).reservoir().capacity(),1e-12);
        var second=advance(reopened,0,0);
        assertEquals(0,second.budget().sourceWater(),1e-12);
        assertEquals(0,second.budget().terminalOutflow(),1e-12);
        assertEquals(3,second.budget().finalStorage(),1e-12);
        assertThrows(IllegalArgumentException.class,()->
            reopened.receive(receipt,2));
    }

    @Test void futureReceiptRemainsParkedUntilItsEpoch() throws IOException {
        var runner=open(directory.resolve("future.chk"),0);
        var receipt=new HydrologyTransferLedgerV2.Key(3,FROM,A);
        runner.receive(receipt,5);
        advance(runner,0,0);
        assertFalse(runner.inboxSnapshot().delivered().contains(receipt));
        advance(runner,0,0);
        assertFalse(runner.inboxSnapshot().delivered().contains(receipt));
        var third=advance(runner,0,0);
        assertTrue(runner.inboxSnapshot().delivered().contains(receipt));
        assertEquals(5,third.budget().finalStorage(),1e-12);
        assertEquals(5,third.budget().sourceWater(),1e-12);
        assertEquals(0,third.budget().terminalOutflow(),1e-12);
    }

    @Test void fullySiltedLakeRollsBackReceiptAndEpoch() throws IOException {
        var path=directory.resolve("rollback.chk");
        var runner=open(path,4);
        var receipt=new HydrologyTransferLedgerV2.Key(1,FROM,A);
        runner.receive(receipt,2);
        String before=runner.worldState();
        assertThrows(IllegalArgumentException.class,()->advance(runner,0,10000));
        assertEquals(0,runner.epoch());
        assertEquals(before,runner.worldState());
        assertFalse(runner.inboxSnapshot().delivered().contains(receipt));
        assertEquals(before,HydrologyWorldRunnerV2.open(path,0,"ignored").worldState());
        var recovered=advance(runner,0,4000);
        assertEquals(3,recovered.budget().terminalOutflow(),1e-12);
        assertTrue(runner.inboxSnapshot().delivered().contains(receipt));
    }

    @Test void topologyChangesAndDuplicateOwnershipFailBeforeCommit() throws IOException {
        var runner=open(directory.resolve("topology.chk"),0);
        var changed=new ArrayList<>(runoff(0));
        changed.set(1,new HydrologyFlowGraphV2.Node(A,0,null,true));
        assertThrows(IllegalArgumentException.class,()->
            runner.advanceClosedSphericalLakeSedimentNetwork(
                "bad",baseline(),changed,water(),sediment(0),
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        var duplicated=new ArrayList<>(runoff(0));
        duplicated.add(duplicated.get(0));
        assertThrows(IllegalArgumentException.class,()->
            runner.advanceClosedSphericalLakeSedimentNetwork(
                "bad",baseline(),duplicated,water(),sediment(0),
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        assertEquals(0,runner.epoch());
        assertEquals("initial",HydrologyLakeSedimentSnapshotV2.decode(
            baseline(),runner.worldState()).water().world());
    }

    @Test void staleRunnerCannotOverwriteCommittedGeometryOrReceipts() throws IOException {
        var path=directory.resolve("stale.chk");
        var first=open(path,4);
        var stale=HydrologyWorldRunnerV2.open(path,0,"ignored");
        advance(first,0,4000);
        assertThrows(IOException.class,()->advance(stale,0,0));
        assertEquals(0,stale.epoch());
        var reopened=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,reopened.epoch());
        assertEquals(3,HydrologyLakeSedimentSnapshotV2.decode(
            baseline(),reopened.worldState()).plan().lakes().get(0)
            .reservoir().capacity(),1e-12);
    }

    @Test void reversedSeasonalEnumerationProducesIdenticalDurablePayload()
            throws IOException {
        var left=open(directory.resolve("left.chk"),4);
        var right=open(directory.resolve("right.chk"),4);
        var reversed=new ArrayList<>(runoff(2));
        Collections.reverse(reversed);
        var one=left.advanceClosedSphericalLakeSedimentNetwork(
            "same",baseline(),runoff(2),water(),sediment(4000),
            SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
        var two=right.advanceClosedSphericalLakeSedimentNetwork(
            "same",baseline(),reversed,water(),sediment(4000),
            SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
        assertEquals(one.budget(),two.budget());
        assertEquals(left.worldState(),right.worldState());
    }

    @Test void nonSnapshotWorldAndNonzeroBaselineAreRejected() throws IOException {
        var path=directory.resolve("invalid.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"not-a-snapshot");
        assertThrows(IllegalArgumentException.class,()->advance(runner,0,0));
        assertEquals(0,runner.epoch());
        var base=baseline();
        var changed=new ArrayList<>(base.routes());
        changed.set(1,new HydrologyFlowGraphV2.Node(A,1,B,false));
        assertThrows(IllegalArgumentException.class,()->
            runner.advanceClosedSphericalLakeSedimentNetwork(
                "bad",new HydrologySphericalLakeBasinsV2.Plan(
                    changed,base.lakes()),runoff(0),water(),sediment(0),
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
    }
    private static HydrologyWorldRunnerV2 openWithTerrain(Path path,double initial,
            Map<GlobalHydrologyAddressV2.Sample,Double> overrides)
            throws IOException {
        var base=baseline();
        String terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("initial",overrides));
        return HydrologyWorldRunnerV2.open(path,0,
            HydrologyLakeSedimentSnapshotV2.encode(base,base,
                new HydrologyLakeStateV2.State(terrain,Map.of(A,initial))));
    }
    private static HydrologyLakeSedimentNetworkV2.Result terrainAdvance(
            HydrologyWorldRunnerV2 runner,double rainfall,double mass,
            HydrologyGlobalSlopeRouterV2.Elevations seed) throws IOException {
        return runner.advanceClosedSphericalLakeSedimentNetworkWithTerrain(
            "terrain-season-"+(runner.epoch()+1),baseline(),seed,
            runoff(rainfall),water(),sediment(mass),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
    }

    @Test void sedimentTerrainWaterAndReceiptPersistInOneCheckpoint() throws IOException {
        var path=directory.resolve("terrain.chk");
        var unrelated=at(7,3);
        var runner=openWithTerrain(path,4,Map.of(unrelated,7.0));
        var receipt=new HydrologyTransferLedgerV2.Key(1,FROM,A);
        runner.receive(receipt,2);
        HydrologyGlobalSlopeRouterV2.Elevations seed=address->0.0;
        var first=terrainAdvance(runner,1,4000,seed);
        assertEquals(4,first.budget().terminalOutflow(),1e-12);
        var reopened=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertTrue(reopened.inboxSnapshot().delivered().contains(receipt));
        var restored=HydrologyLakeSedimentSnapshotV2.decode(
            baseline(),reopened.worldState());
        var terrain=HydrologyTerrainOverlayV2.decode(restored.water().world());
        assertEquals("terrain-season-1",terrain.world());
        assertEquals(2,terrain.elevations().get(A),1e-12);
        assertEquals(7,terrain.elevations().get(unrelated),1e-12);
        assertEquals(3,restored.water().volumes().get(A),1e-12);

        var second=terrainAdvance(reopened,0,1000,seed);
        assertEquals(0.5,second.displaced().get(A),1e-12);
        assertEquals(0.5,second.budget().terminalOutflow(),1e-12);
        var after=HydrologyLakeSedimentSnapshotV2.decode(
            baseline(),HydrologyWorldRunnerV2.open(path,0,"ignored").worldState());
        assertEquals(2.5,HydrologyTerrainOverlayV2.decode(
            after.water().world()).elevations().get(A),1e-12);
        assertEquals(2.5,after.water().volumes().get(A),1e-12);
    }

    @Test void mismatchedSeedOrStoredTerrainRejectsWithoutConsumingReceipt()
            throws IOException {
        var path=directory.resolve("bad-terrain.chk");
        var runner=openWithTerrain(path,4,Map.of());
        var receipt=new HydrologyTransferLedgerV2.Key(1,FROM,A);
        runner.receive(receipt,2);
        String before=runner.worldState();
        assertThrows(IllegalArgumentException.class,()->
            terrainAdvance(runner,0,4000,address->address.equals(A)?1.0:0.0));
        assertEquals(0,runner.epoch());
        assertEquals(before,runner.worldState());
        assertFalse(runner.inboxSnapshot().delivered().contains(receipt));
        assertEquals(before,HydrologyWorldRunnerV2.open(path,0,"ignored").worldState());
        terrainAdvance(runner,0,4000,address->0.0);
        assertTrue(runner.inboxSnapshot().delivered().contains(receipt));

        var wrong=openWithTerrain(directory.resolve("wrong-overlay.chk"),0,
            Map.of(A,1.0));
        assertThrows(IllegalArgumentException.class,()->
            terrainAdvance(wrong,0,0,address->0.0));
        assertEquals(0,wrong.epoch());
    }

    @Test void terrainModeRejectsNonTerrainWorldAndKeepsCheckpoint()
            throws IOException {
        var path=directory.resolve("not-terrain.chk");
        var runner=open(path,0);
        var old=runner.worldState();
        assertThrows(IllegalArgumentException.class,()->
            terrainAdvance(runner,0,0,address->0.0));
        assertEquals(old,runner.worldState());
        assertEquals(0,runner.epoch());
    }

    @Test void hundredSeasonRestartAndDeferredReceiptConservation()
            throws IOException {
        var continuous=open(directory.resolve("continuous.chk"),2);
        var path=directory.resolve("restarted.chk");
        var restarted=open(path,2);
        var receipts=new ArrayList<HydrologyTransferLedgerV2.Key>();
        for (int epoch=10;epoch<=90;epoch+=10) {
            var key=new HydrologyTransferLedgerV2.Key(epoch,FROM,A);
            receipts.add(key);
            continuous.receive(key,1.25);
            restarted.receive(key,1.25);
        }
        double input=2, output=0;
        for (int epoch=1;epoch<=100;epoch++) {
            if (epoch%7==0)
                restarted=HydrologyWorldRunnerV2.open(path,0,"ignored");
            double rainfall=(epoch%5)*0.5;
            var one=advance(continuous,rainfall,1);
            var two=advance(restarted,rainfall,1);
            assertEquals(one.budget(),two.budget(),
                "Seasonal budget diverged at epoch "+epoch);
            assertEquals(continuous.worldState(),restarted.worldState(),
                "Snapshot diverged at epoch "+epoch);
            input+=one.budget().sourceWater()+one.budget().externalInflow();
            output+=one.budget().terminalOutflow()+one.budget().evaporation();
        }
        var finalState=HydrologyLakeSedimentSnapshotV2.decode(
            baseline(),restarted.worldState());
        double retained=finalState.water().volumes().get(A);
        assertEquals(input,output+retained,1e-7);
        assertEquals(100,restarted.epoch());
        assertTrue(restarted.inboxSnapshot().delivered().containsAll(receipts));
        assertEquals(receipts.size(),restarted.inboxSnapshot().delivered().size());
        assertEquals(continuous.worldState(),
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState());
    }

}
