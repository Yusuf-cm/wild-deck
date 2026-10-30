package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyDrainageActivationV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return sources;
    }
    private static HydrologySphericalRouteAtlasV2 original() {
        return HydrologySphericalRouteAtlasV2.compile(
            planet(),a->a.equals(at(0,2))||a.equals(at(4,2))?0:10,
            Set.of(at(0,2)));
    }
    private static HydrologySphericalAtlasRebuildAuditV2.Result changed(
            HydrologySphericalRouteAtlasV2 old) {
        return HydrologySphericalAtlasRebuildAuditV2.rebuild(
            old,planet(),a->a.equals(at(0,2))||a.equals(at(4,2))?0:10,
            Set.of(at(0,2),at(4,2)));
    }
    private static String bound(HydrologySphericalRouteAtlasV2 old,
                                Map<GlobalHydrologyAddressV2.Sample,Double> lakes) {
        return HydrologyDrainageActivationV2.bind(old,
            HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State("world",lakes)));
    }

    @Test void changedRouteLandsOldEdgeWaterThenAtomicallyBindsNewTopology(
            @TempDir Path dir) throws Exception {
        var old=original();
        var audit=changed(old);
        var from=at(4,2);
        var destination=old.routeAt(from).downstream();
        assertNotNull(destination);
        assertNotEquals(old.routeAt(from).downstream(),
                        audit.rebuilt().routeAt(from).downstream());
        var key=new HydrologyTransferLedgerV2.Key(1,from,destination);
        var file=dir.resolve("drainage.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,Map.of()));
        assertTrue(runner.receive(key,11));
        var committed=runner.activateReceiptOnlyDrainage(old,audit);
        assertEquals(1,runner.epoch());
        assertEquals(11,committed.budget().input(),1e-12);
        assertEquals(11,committed.budget().outlet(),1e-12);
        assertEquals(0,committed.budget().frontier(),1e-12);
        assertEquals(11,committed.landings().byDestination().get(destination),1e-12);
        assertTrue(runner.inboxSnapshot().delivered().contains(key));
        assertEquals(0,runner.receiptBudget().pendingVolume(),1e-12);
        runner.verifyBoundDrainage(audit.rebuilt());
        assertThrows(IllegalArgumentException.class,()->runner.verifyBoundDrainage(old));

        var reopened=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(1,reopened.epoch());
        reopened.verifyBoundDrainage(audit.rebuilt());
        assertEquals(0,reopened.receiptBudget().pendingVolume(),1e-12);
        assertThrows(IllegalArgumentException.class,()->reopened.receive(key,11));
        assertThrows(IllegalArgumentException.class,()->
            reopened.activateReceiptOnlyDrainage(old,audit));
    }

    @Test void futureOldEdgeReceiptRejectsWithoutLosingCheckpointWater(
            @TempDir Path dir) throws Exception {
        var old=original();
        var file=dir.resolve("future.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,Map.of()));
        var from=at(4,2);
        var key=new HydrologyTransferLedgerV2.Key(
            3,from,old.routeAt(from).downstream());
        runner.receive(key,7);
        var before=runner.inboxSnapshot();
        var world=runner.worldState();
        assertThrows(IllegalArgumentException.class,()->
            runner.activateReceiptOnlyDrainage(old,changed(old)));
        assertEquals(0,runner.epoch());
        assertEquals(world,runner.worldState());
        assertEquals(before,runner.inboxSnapshot());
        var reopened=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(before,reopened.inboxSnapshot());
        assertEquals(7,reopened.receiptBudget().pendingVolume(),1e-12);
    }

    @Test void nonemptyLakeStorageRejectsAndPreservesCheckpoint(
            @TempDir Path dir) throws Exception {
        var old=original();
        var file=dir.resolve("lake.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,Map.of(at(0,2),5.0)));
        var state=runner.worldState();
        assertThrows(IllegalArgumentException.class,()->
            runner.activateReceiptOnlyDrainage(old,changed(old)));
        assertEquals(state,HydrologyWorldRunnerV2.open(file,0,"ignored").worldState());
        assertEquals(0,runner.epoch());
    }

    @Test void forgedOldEdgeRejectsAndKeepsReceipt(
            @TempDir Path dir) throws Exception {
        var old=original();
        var from=at(4,2);
        var wrong=HydrologySphericalTopologyV2.neighbors(from).stream()
            .filter(a->!a.equals(old.routeAt(from).downstream()))
            .findFirst().orElseThrow();
        var file=dir.resolve("forged.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,Map.of()));
        runner.receive(new HydrologyTransferLedgerV2.Key(1,from,wrong),4);
        assertThrows(IllegalArgumentException.class,()->
            runner.activateReceiptOnlyDrainage(old,changed(old)));
        assertEquals(0,runner.epoch());
        assertEquals(4,HydrologyWorldRunnerV2.open(file,0,"ignored")
            .receiptBudget().pendingVolume(),1e-12);
    }

    @Test void duplicateConfluenceReceiptsCountOnceAcrossRestart(
            @TempDir Path dir) throws Exception {
        var old=original();
        var confluence=old.routes().stream()
            .filter(n->n.downstream()!=null)
            .collect(java.util.stream.Collectors.groupingBy(
                HydrologyFlowGraphV2.Node::downstream))
            .values().stream().filter(group->group.size()>=2)
            .findFirst().orElseThrow();
        var to=confluence.get(0).downstream();
        var a=new HydrologyTransferLedgerV2.Key(1,confluence.get(0).address(),to);
        var b=new HydrologyTransferLedgerV2.Key(1,confluence.get(1).address(),to);
        var file=dir.resolve("confluence.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,Map.of()));
        assertTrue(runner.receive(b,5));
        assertTrue(runner.receive(a,3));
        assertFalse(runner.receive(a,3));
        var committed=runner.activateReceiptOnlyDrainage(old,changed(old));
        assertEquals(8,committed.budget().input(),1e-12);
        assertEquals(8,committed.budget().outlet(),1e-12);
        assertEquals(8,committed.landings().pendingVolume(),1e-12);
        assertEquals(2,committed.landings().landings().size());
        assertEquals(0,HydrologyWorldRunnerV2.open(file,0,"ignored")
            .receiptBudget().pendingVolume(),1e-12);
    }

    @Test void verifiedReceiptRejectsStaleTopologyAndPersistsCurrentEdge(
            @TempDir Path dir) throws Exception {
        var old=original();
        var audit=changed(old);
        var file=dir.resolve("verified.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,bound(old,Map.of()));
        runner.activateReceiptOnlyDrainage(old,audit);
        var oldEdge=new HydrologyTransferLedgerV2.Key(
            2,at(4,2),old.routeAt(at(4,2)).downstream());
        assertThrows(IllegalArgumentException.class,()->
            runner.receiveVerifiedDrainage(audit.rebuilt(),oldEdge,6));
        assertThrows(IllegalArgumentException.class,()->
            runner.receiveVerifiedDrainage(old,oldEdge,6));
        var source=audit.rebuilt().routes().stream()
            .filter(n->n.downstream()!=null).findFirst().orElseThrow();
        var valid=new HydrologyTransferLedgerV2.Key(
            2,source.address(),source.downstream());
        assertTrue(runner.receiveVerifiedDrainage(audit.rebuilt(),valid,3));
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertFalse(restored.receiveVerifiedDrainage(audit.rebuilt(),valid,3));
        assertEquals(3,restored.receiptBudget().pendingVolume(),1e-12);
        assertEquals(1,restored.epoch());
    }

    @Test void codecRejectsUnboundAndNoncanonicalStates() {
        var old=original();
        var payload=bound(old,Map.of());
        var decoded=HydrologyDrainageActivationV2.decode(payload);
        assertEquals(payload,HydrologyDrainageActivationV2.encode(decoded));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageActivationV2.decode("world"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageActivationV2.decode(payload+"extra"));
    }
}
