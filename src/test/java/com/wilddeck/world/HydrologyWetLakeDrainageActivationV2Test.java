package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyWetLakeDrainageActivationV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUT=at(0,2), PIT=at(4,2);
    private static final double RADIUS=1000;
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return sources;
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations terrain(double pit) {
        return a->a.equals(OUT)?0:a.equals(PIT)?pit:10;
    }
    private static HydrologySphericalRouteAtlasV2 atlas(double pit) {
        return HydrologySphericalRouteAtlasV2.compile(
            planet(),terrain(pit),Set.of(OUT));
    }
    private static HydrologySphericalLakeBasinsV2.Plan plan(double pit) {
        return HydrologySphericalLakeBasinsV2.extract(
            planet(),terrain(pit),Set.of(OUT),RADIUS);
    }
    private static HydrologySphericalAtlasRebuildAuditV2.Result audit(
            HydrologySphericalRouteAtlasV2 old,double pit) {
        return HydrologySphericalAtlasRebuildAuditV2.rebuild(
            old,planet(),terrain(pit),Set.of(OUT));
    }
    private static String bound(HydrologySphericalRouteAtlasV2 old,double volume) {
        return HydrologyWetLakeDrainageActivationV2.bind(old,plan(-5),
            HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State(
                "world",Map.of(PIT,volume))));
    }
    private static HydrologyTransferLedgerV2.Key receipt(
            HydrologySphericalRouteAtlasV2 old,long epoch) {
        var node=old.routes().stream().filter(n->n.downstream()!=null)
            .findFirst().orElseThrow();
        return new HydrologyTransferLedgerV2.Key(
            epoch,node.address(),node.downstream());
    }

    @Test void wetSedimentRemapAndOldEdgeReceiptsCommitTogether(@TempDir Path dir)
            throws Exception {
        var old=atlas(-5);
        var oldPlan=plan(-5);
        var newPlan=plan(-4);
        assertEquals(1,oldPlan.lakes().size());
        assertEquals(1,newPlan.lakes().size());
        double oldCapacity=oldPlan.lakes().get(0).reservoir().capacity();
        double newCapacity=newPlan.lakes().get(0).reservoir().capacity();
        double oldVolume=oldCapacity;
        assertTrue(oldVolume>newCapacity);
        var file=dir.resolve("wet.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,oldVolume));
        var key=receipt(old,1);
        assertTrue(runner.receive(key,7));
        assertFalse(runner.receive(key,7));
        var committed=runner.activateWetLakeDrainage(old,audit(old,-4),oldPlan,newPlan);
        assertEquals(1,runner.epoch());
        assertEquals(oldVolume-newCapacity,committed.remap().released(),1e-7);
        assertEquals(newCapacity,committed.lakeFlow().budget().finalStorage(),1e-7);
        assertEquals(oldVolume-newCapacity+7,
            committed.lakeFlow().budget().terminalOutflow(),1e-7);
        assertEquals(0,committed.lakeFlow().budget().residual(),1e-6);
        assertEquals(7,committed.landings().pendingVolume(),1e-12);
        assertEquals(0,runner.receiptBudget().pendingVolume(),0);
        runner.verifyBoundWetLakeDrainage(audit(old,-4).rebuilt(),newPlan);
        assertThrows(IllegalArgumentException.class,()->
            runner.verifyBoundWetLakeDrainage(audit(old,-4).rebuilt(),oldPlan));
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(1,restored.epoch());
        restored.verifyBoundWetLakeDrainage(audit(old,-4).rebuilt(),newPlan);
        assertEquals(0,restored.receiptBudget().pendingVolume(),0);
        var state=HydrologyDrainageActivationV2.decode(restored.worldState());
        assertEquals(newCapacity,HydrologyLakeStateV2.decode(
            state.lakeState()).volumes().get(PIT),1e-7);
        assertTrue(restored.inboxSnapshot().delivered().contains(key));
    }

    @Test void disappearingWetCellRejectsAndPreservesCheckpointAndReceipt(
            @TempDir Path dir) throws Exception {
        var old=atlas(-5);
        var oldPlan=plan(-5);
        var newPlan=plan(10);
        assertTrue(newPlan.lakes().isEmpty());
        var file=dir.resolve("disappeared.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,5));
        var key=receipt(old,1);
        runner.receive(key,9);
        var before=runner.worldState();
        var inbox=runner.inboxSnapshot();
        assertThrows(IllegalArgumentException.class,()->
            runner.activateWetLakeDrainage(old,audit(old,10),oldPlan,newPlan));
        assertEquals(0,runner.epoch());
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(before,restored.worldState());
        assertEquals(inbox,restored.inboxSnapshot());
        assertEquals(9,restored.receiptBudget().pendingVolume(),0);
    }

    @Test void futureOldEdgeAndStaleRunnerFailWithoutLosingWater(
            @TempDir Path dir) throws Exception {
        var old=atlas(-5);
        var oldPlan=plan(-5);
        var newPlan=plan(-4);
        var file=dir.resolve("future.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,5));
        runner.receive(receipt(old,3),6);
        assertThrows(IllegalArgumentException.class,()->
            runner.activateWetLakeDrainage(old,audit(old,-4),oldPlan,newPlan));
        assertEquals(6,HydrologyWorldRunnerV2.open(file,0,"ignored")
            .receiptBudget().pendingVolume(),0);
        var stale=HydrologyWorldRunnerV2.open(dir.resolve("stale.chk"),0,
            bound(old,5));
        var writer=HydrologyWorldRunnerV2.open(dir.resolve("stale.chk"),0,"ignored");
        writer.receive(receipt(old,1),8);
        assertThrows(java.io.IOException.class,()->
            stale.activateWetLakeDrainage(old,audit(old,-4),oldPlan,newPlan));
        assertEquals(8,HydrologyWorldRunnerV2.open(
            dir.resolve("stale.chk"),0,"ignored").receiptBudget().pendingVolume(),0);
    }

    @Test void wetLakeConfluenceReceiptsAreCountedOnce(@TempDir Path dir)
            throws Exception {
        var old=atlas(-5);
        var groups=new HashMap<GlobalHydrologyAddressV2.Sample,
                               List<HydrologyFlowGraphV2.Node>>();
        for(var node:old.routes())
            if(node.downstream()!=null)
                groups.computeIfAbsent(node.downstream(),ignored->new ArrayList<>())
                    .add(node);
        var pair=groups.values().stream().filter(g->g.size()>1)
            .findFirst().orElseThrow();
        var a=new HydrologyTransferLedgerV2.Key(
            1,pair.get(0).address(),pair.get(0).downstream());
        var b=new HydrologyTransferLedgerV2.Key(
            1,pair.get(1).address(),pair.get(1).downstream());
        var file=dir.resolve("confluence.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,5));
        assertTrue(runner.receive(b,5));
        assertTrue(runner.receive(a,3));
        assertFalse(runner.receive(a,3));
        var committed=runner.activateWetLakeDrainage(
            old,audit(old,-4),plan(-5),plan(-4));
        assertEquals(8,committed.landings().pendingVolume(),0);
        assertEquals(2,committed.landings().landings().size());
        assertEquals(13,committed.lakeFlow().budget().finalStorage()+
            committed.lakeFlow().budget().terminalOutflow(),1e-7);
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(0,restored.receiptBudget().pendingVolume(),0);
        assertTrue(restored.inboxSnapshot().delivered().containsAll(Set.of(a,b)));
    }

    @Test void forgedOldEdgeRejectsWetLakeTransitionWithoutMutation(
            @TempDir Path dir) throws Exception {
        var old=atlas(-5);
        var valid=receipt(old,1);
        var wrong=HydrologySphericalTopologyV2.neighbors(valid.from()).stream()
            .filter(n->!n.equals(valid.to())).findFirst().orElseThrow();
        var file=dir.resolve("forged.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,5));
        runner.receive(new HydrologyTransferLedgerV2.Key(1,valid.from(),wrong),4);
        var before=runner.worldState();
        assertThrows(IllegalArgumentException.class,()->
            runner.activateWetLakeDrainage(old,audit(old,-4),plan(-5),plan(-4)));
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(before,restored.worldState());
        assertEquals(0,restored.epoch());
        assertEquals(4,restored.receiptBudget().pendingVolume(),0);
    }

    @Test void unboundWetCheckpointAndMismatchedOldGeometryReject(
            @TempDir Path dir) throws Exception {
        var old=atlas(-5);
        var file=dir.resolve("legacy.chk");
        var legacy=HydrologyDrainageActivationV2.bind(old,
            HydrologyLakeStateV2.encode(
                new HydrologyLakeStateV2.State("world",Map.of(PIT,5.0))));
        var runner=HydrologyWorldRunnerV2.open(file,0,legacy);
        assertThrows(IllegalArgumentException.class,()->
            runner.activateWetLakeDrainage(old,audit(old,-4),plan(-5),plan(-4)));
        assertEquals(legacy,HydrologyWorldRunnerV2.open(file,0,"ignored").worldState());
        var boundFile=dir.resolve("geometry.chk");
        var boundRunner=HydrologyWorldRunnerV2.open(boundFile,0,bound(old,5));
        assertThrows(IllegalArgumentException.class,()->
            boundRunner.activateWetLakeDrainage(old,audit(old,-4),plan(-4),plan(-4)));
        assertEquals(0,boundRunner.epoch());
    }

    @Test void rejectsForgedLakeRoutesAndSeasonalPayload(@TempDir Path dir)
            throws Exception {
        var old=atlas(-5);
        var oldPlan=plan(-5);
        var newPlan=plan(-4);
        var wrongPlan=new HydrologySphericalLakeBasinsV2.Plan(
            oldPlan.routes(),newPlan.lakes());
        var file=dir.resolve("bad.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,5));
        assertThrows(IllegalArgumentException.class,()->
            runner.activateWetLakeDrainage(old,audit(old,10),oldPlan,wrongPlan));
        var seasonal=HydrologySeasonalLakeNetworkV2.initialState("world");
        var seasonalFile=dir.resolve("seasonal.chk");
        var seasonalRunner=HydrologyWorldRunnerV2.open(seasonalFile,0,
            HydrologyDrainageActivationV2.bind(old,seasonal));
        assertThrows(IllegalArgumentException.class,()->
            seasonalRunner.activateWetLakeDrainage(old,audit(old,-4),
                oldPlan,newPlan));
        assertEquals(0,seasonalRunner.epoch());
    }
}
