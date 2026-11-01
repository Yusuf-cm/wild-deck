package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyDrainageTransitionGateV2Test {
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
            HydrologySphericalRouteAtlasV2 previous,boolean newOutlet) {
        return HydrologySphericalAtlasRebuildAuditV2.rebuild(
            previous,planet(),elevations(),
            newOutlet?Set.of(at(0,2),at(4,2)):Set.of(at(0,2)));
    }

    private static HydrologyTransferLedgerV2.Key receipt(
            HydrologySphericalRouteAtlasV2 previous) {
        var from=at(4,2);
        return new HydrologyTransferLedgerV2.Key(
            1,from,previous.routeAt(from).downstream());
    }

    @Test void unchangedAtlasAllowsExactlyOneParkedTransferWithoutConsumingIt() {
        var previous=original();
        var inbox=new HydrologyEpochInboxV2();
        var key=receipt(previous);
        assertTrue(inbox.receive(key,7));
        var snapshot=inbox.snapshot();
        var result=HydrologyDrainageTransitionGateV2.verifyPending(
            previous,audit(previous,false),snapshot);
        assertEquals(1,result.pendingReceipts());
        assertEquals(7,result.pendingVolume(),1e-12);
        assertEquals(snapshot,inbox.snapshot());
        assertFalse(inbox.snapshot().delivered().contains(key));
    }

    @Test void changedOutletRejectsOldParkedEdgeWithoutLosingWater() {
        var previous=original();
        var inbox=new HydrologyEpochInboxV2();
        var key=receipt(previous);
        inbox.receive(key,9);
        var changed=audit(previous,true);
        assertTrue(changed.affectedSamples().contains(key.from()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageTransitionGateV2.verifyPending(
                previous,changed,inbox.snapshot()));
        assertEquals(9,HydrologyReceiptBudgetV2.summarize(inbox.snapshot())
            .pendingVolume(),1e-12);
        assertTrue(inbox.snapshot().delivered().isEmpty());
    }

    @Test void alreadyDeliveredReceiptsAreNotReinjectedOnTransition() {
        var previous=original();
        var inbox=new HydrologyEpochInboxV2();
        var key=receipt(previous);
        inbox.receive(key,5);
        assertEquals(5,inbox.claimAvailable(1,Set.of(key.to())).get(key.to()),1e-12);
        var verified=HydrologyDrainageTransitionGateV2.verifyPending(
            previous,audit(previous,true),inbox.snapshot());
        assertEquals(0,verified.pendingReceipts());
        assertEquals(0,verified.pendingVolume(),1e-12);
        assertEquals(5,HydrologyReceiptBudgetV2.summarize(inbox.snapshot())
            .deliveredVolume(),1e-12);
    }

    @Test void forgedAdjacentReceiptIsRejectedEvenOnUnchangedTerrain() {
        var previous=original();
        var from=at(4,2);
        var actual=previous.routeAt(from).downstream();
        var other=HydrologySphericalTopologyV2.neighbors(from).stream()
            .filter(a->!a.equals(actual)).findFirst().orElseThrow();
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,from,other),3);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageTransitionGateV2.verifyPending(
                previous,audit(previous,false),inbox.snapshot()));
    }

    @Test void crossRegionParkedTransferIsOwnedOnceAndNotReplayed() {
        var previous=original();
        // A canonical edge crosses the longitude-region ownership boundary.
        var edge=previous.routes().stream()
            .filter(n->n.downstream()!=null && n.address().y()>0 &&
                n.address().y()<4 && n.downstream().y()>0 &&
                n.downstream().y()<4 &&
                ((n.address().x()==0?0:(n.address().x()-1)/2) !=
                 (n.downstream().x()==0?0:(n.downstream().x()-1)/2)))
            .findFirst().orElseThrow();
        var key=new HydrologyTransferLedgerV2.Key(
            1,edge.address(),edge.downstream());
        var inbox=new HydrologyEpochInboxV2();
        assertTrue(inbox.receive(key,4));
        assertFalse(inbox.receive(key,4));
        var result=HydrologyDrainageTransitionGateV2.verifyPending(
            previous,audit(previous,false),inbox.snapshot());
        assertEquals(1,result.pendingReceipts());
        assertEquals(4,result.pendingVolume(),1e-12);
        assertEquals(4,HydrologyReceiptBudgetV2.summarize(inbox.snapshot())
            .totalVolume(),1e-12);
    }

    @Test void checkpointRestartPreservesTransitionRejectionAndPendingWater(
            @TempDir Path directory) throws Exception {
        var previous=original();
        var file=directory.resolve("hydrology.checkpoint");
        var runner=HydrologyWorldRunnerV2.open(file,0,"original-world");
        var key=receipt(previous);
        assertTrue(runner.receive(key,11));
        var reopened=HydrologyWorldRunnerV2.open(file,0,"ignored-on-restart");
        assertEquals(0,reopened.epoch());
        assertEquals(11,reopened.receiptBudget().pendingVolume(),1e-12);
        assertEquals(1,reopened.verifyDrainageTransition(
            previous,audit(previous,false)).pendingReceipts());
        assertThrows(IllegalArgumentException.class,()->
            reopened.verifyDrainageTransition(previous,audit(previous,true)));
        assertEquals(11,reopened.receiptBudget().pendingVolume(),1e-12);
        assertEquals("original-world",reopened.worldState());
    }
}
