package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyDrainageLandingPlanV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),1));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),1));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),1));
        return sources;
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations elevations() {
        return a->a.equals(at(0,2))||a.equals(at(4,2))?0:10;
    }
    private static HydrologySphericalRouteAtlasV2 original() {
        return HydrologySphericalRouteAtlasV2.compile(
            planet(),elevations(),Set.of(at(0,2)));
    }
    private static HydrologySphericalAtlasRebuildAuditV2.Result audit(
            HydrologySphericalRouteAtlasV2 previous,boolean changed) {
        return HydrologySphericalAtlasRebuildAuditV2.rebuild(
            previous,planet(),elevations(),
            changed?Set.of(at(0,2),at(4,2)):Set.of(at(0,2)));
    }

    @Test void changedSourceRouteKeepsPreviouslyDispatchedWaterAtOldDestination() {
        var old=original();
        var from=at(4,2);
        var destination=old.routeAt(from).downstream();
        var key=new HydrologyTransferLedgerV2.Key(1,from,destination);
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(key,11);
        var changed=audit(old,true);
        assertFalse(HydrologyDrainageTransitionReportV2.inspect(
            old,changed,inbox.snapshot()).safeForPendingWater());
        var before=inbox.snapshot();
        var plan=HydrologyDrainageLandingPlanV2.inspect(old,changed,before);
        assertEquals(11,plan.pendingVolume(),1e-12);
        assertEquals(11,plan.byDestination().get(destination),1e-12);
        assertEquals(List.of(key),plan.landings().stream()
            .map(HydrologyDrainageLandingPlanV2.Landing::receipt).toList());
        assertEquals(changed.rebuilt().routeAt(destination).downstream(),
            plan.landings().get(0).onward());
        assertTrue(plan.landings().get(0).affected());
        assertNotEquals(plan.previousTopology(),plan.rebuiltTopology());
        assertEquals(before,inbox.snapshot());
    }

    @Test void confluenceAggregatesUniqueReceiptsDeterministically() {
        var old=original();
        var confluence=old.routes().stream()
            .filter(n->n.downstream()!=null)
            .collect(java.util.stream.Collectors.groupingBy(
                HydrologyFlowGraphV2.Node::downstream))
            .values().stream().filter(group->group.size()>=2)
            .findFirst().orElseThrow();
        var destination=confluence.get(0).downstream();
        var a=new HydrologyTransferLedgerV2.Key(
            1,confluence.get(0).address(),destination);
        var b=new HydrologyTransferLedgerV2.Key(
            1,confluence.get(1).address(),destination);
        var inbox=new HydrologyEpochInboxV2();
        assertTrue(inbox.receive(a,3));
        assertFalse(inbox.receive(a,3));
        assertTrue(inbox.receive(b,5));
        var reversed=new HydrologyEpochInboxV2();
        reversed.receive(b,5);
        reversed.receive(a,3);
        var audit=audit(old,false);
        var first=HydrologyDrainageLandingPlanV2.inspect(old,audit,inbox.snapshot());
        var second=HydrologyDrainageLandingPlanV2.inspect(old,audit,reversed.snapshot());
        assertEquals(first,second);
        assertEquals(2,first.landings().size());
        assertEquals(8,first.pendingVolume(),1e-12);
        assertEquals(Map.of(destination,8.0),first.byDestination());
        inbox.claimAvailable(1,Set.of(destination));
        var delivered=HydrologyDrainageLandingPlanV2.inspect(old,audit,inbox.snapshot());
        assertTrue(delivered.landings().isEmpty());
        assertEquals(0,delivered.pendingVolume(),1e-12);
    }

    @Test void forgedOldEdgeIsRejectedWithoutMutatingCheckpoint(
            @TempDir Path directory) throws Exception {
        var old=original();
        var from=at(4,2);
        var wrong=HydrologySphericalTopologyV2.neighbors(from).stream()
            .filter(a->!a.equals(old.routeAt(from).downstream()))
            .findFirst().orElseThrow();
        var file=directory.resolve("forged.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,"old-world");
        var key=new HydrologyTransferLedgerV2.Key(1,from,wrong);
        runner.receive(key,4);
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        var before=restored.inboxSnapshot();
        assertThrows(IllegalArgumentException.class,()->
            restored.planDrainageLandings(old,audit(old,false)));
        assertEquals(before,restored.inboxSnapshot());
        assertEquals("old-world",restored.worldState());
        assertEquals(4,restored.receiptBudget().pendingVolume(),1e-12);
    }
}
