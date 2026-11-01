package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentCrossRegionAuditV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(0,2),
        B=at(1,2), D=at(1,3);
    private static HydrologySedimentReceiptInboxV2.Receipt edge(
            long source, long arrival, GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(source,arrival,from,to);
    }
    private static HydrologySedimentFrontierTransfersV2.Outgoing outgoing(
            HydrologySedimentReceiptInboxV2.Receipt key, double mass) {
        return new HydrologySedimentFrontierTransfersV2.Outgoing(Map.of(key,mass),mass);
    }
    private static HydrologySedimentReceiptInboxV2.Snapshot receive(
            HydrologySedimentReceiptInboxV2.Snapshot snapshot,
            HydrologySedimentReceiptInboxV2.Receipt key,double mass) {
        return HydrologySedimentReceiptInboxV2.receive(snapshot,key,mass);
    }

    @Test void confluenceMovesBetweenTransitAwaitingAndDeliveredWithoutDoubleCounting() {
        var first=edge(0,1,A,B);
        var second=edge(0,1,D,B);
        var out=Map.of("sender",new HydrologySedimentFrontierTransfersV2.Outgoing(
            Map.of(first,2.0,second,3.0),5));
        var transit=HydrologySedimentCrossRegionAuditV2.reconcile(out,Map.of());
        assertEquals(5,transit.sentMass(),1e-12);
        assertEquals(5,transit.inTransitMass(),1e-12);
        assertEquals(2,transit.inTransitCount());

        var partial=receive(HydrologySedimentReceiptInboxV2.empty(),first,2);
        var awaiting=HydrologySedimentCrossRegionAuditV2.reconcile(out,
            Map.of("receiver",partial));
        assertEquals(2,awaiting.awaitingMass(),1e-12);
        assertEquals(3,awaiting.inTransitMass(),1e-12);
        var complete=receive(partial,second,3);
        var claimed=HydrologySedimentReceiptInboxV2.claimAvailable(
            complete,1,List.of(B));
        var delivered=HydrologySedimentCrossRegionAuditV2.reconcile(out,
            Map.of("receiver",claimed.snapshot()));
        assertEquals(5,delivered.deliveredMass(),1e-12);
        assertEquals(0,delivered.inTransitMass(),1e-12);
        assertEquals(0,delivered.awaitingMass(),1e-12);
        assertEquals(2,delivered.deliveredCount());
    }

    @Test void repeatedOutboxIdentityIsNotTwoPhysicalTransfers() {
        var key=edge(0,1,A,B);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCrossRegionAuditV2.reconcile(
                Map.of("one",outgoing(key,2),"two",outgoing(key,2)),Map.of()));
    }

    @Test void crossResolutionSenderForkFailsEvenWithDifferentReceiptIds() {
        var coarse=edge(0,1,A,B);
        var fineAlias=edge(0,1,fine(0,4),fine(1,4));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCrossRegionAuditV2.reconcile(
                Map.of("coarse",outgoing(coarse,2),"fine",outgoing(fineAlias,3)),Map.of()));
    }

    @Test void twoReceivingRegionsCannotOwnSameGeographicVertex() {
        var coarse=edge(0,1,A,B);
        var fineEdge=edge(0,1,fine(2,5),fine(2,4));
        var first=receive(HydrologySedimentReceiptInboxV2.empty(),coarse,2);
        var second=receive(HydrologySedimentReceiptInboxV2.empty(),fineEdge,3);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCrossRegionAuditV2.reconcile(
                Map.of("coarse",outgoing(coarse,2),"fine",outgoing(fineEdge,3)),
                Map.of("receiver1",first,"receiver2",second)));
    }

    @Test void alteredReceiverMassUnknownReceiptAndSenderBudgetFailClosed() {
        var key=edge(0,1,A,B);
        var sent=Map.of("source",outgoing(key,2));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCrossRegionAuditV2.reconcile(sent,
                Map.of("receiver",receive(HydrologySedimentReceiptInboxV2.empty(),key,3))));
        var ghost=edge(1,2,D,B);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCrossRegionAuditV2.reconcile(sent,
                Map.of("receiver",receive(HydrologySedimentReceiptInboxV2.empty(),ghost,2))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCrossRegionAuditV2.reconcile(
                Map.of("source",new HydrologySedimentFrontierTransfersV2.Outgoing(
                    Map.of(key,2.0),99)),Map.of()));
    }

    @Test void durableAuditReadsRealReceiverCheckpointAndDeliveryState() throws IOException {
        var key=edge(0,1,A,B);
        var sent=Map.of("source",outgoing(key,7));
        Path path=directory.resolve("receiver.chk");
        var initial=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld("opaque-world"),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,initial);
        assertEquals(7,HydrologySedimentCrossRegionAuditV2.reconcileDurable(
            sent,Map.of("receiver",path)).inTransitMass(),1e-12);
        var pending=HydrologySedimentCheckpointV2.receive(path,initial,key,7);
        assertEquals(7,HydrologySedimentCrossRegionAuditV2.reconcileDurable(
            sent,Map.of("receiver",path)).awaitingMass(),1e-12);
        var state=HydrologySedimentCheckpointV2.decode(pending.worldState());
        var claimed=HydrologySedimentReceiptInboxV2.claimAvailable(
            state.sediment(),1,List.of(B));
        var next=new HydrologyCheckpointV2.Checkpoint(1,
            HydrologySedimentCheckpointV2.encode(state.world(),claimed.snapshot()),
            pending.inboxState());
        HydrologyCheckpointStoreV2.saveNext(path,pending,next);
        assertEquals(7,HydrologySedimentCrossRegionAuditV2.reconcileDurable(
            sent,Map.of("receiver",path)).deliveredMass(),1e-12);
        assertEquals(1,HydrologyCheckpointStoreV2.load(path).epoch());
    }

    @Test void seededConfluencesConserveAcrossUnloadedDestinations() {
        var random=new Random(920177);
        for (int i=0;i<200;i++) {
            double left=random.nextInt(10000)/100.0;
            double right=random.nextInt(10000)/100.0;
            var a=edge(i,i+1,A,B);
            var d=edge(i,i+1,D,B);
            var sent=Map.of("source",new HydrologySedimentFrontierTransfersV2.Outgoing(
                Map.of(a,left,d,right),left+right));
            var parked=receive(HydrologySedimentReceiptInboxV2.empty(),a,left);
            parked=receive(parked,d,right);
            var waiting=HydrologySedimentReceiptInboxV2.claimAvailable(
                parked,i+1,List.of());
            var report=HydrologySedimentCrossRegionAuditV2.reconcile(
                sent,Map.of("receiver",waiting.snapshot()));
            assertEquals(left+right,report.awaitingMass(),1e-9);
            var delivered=HydrologySedimentReceiptInboxV2.claimAvailable(
                waiting.snapshot(),i+2,List.of(B));
            var finalReport=HydrologySedimentCrossRegionAuditV2.reconcile(
                sent,Map.of("receiver",delivered.snapshot()));
            assertEquals(left+right,finalReport.deliveredMass(),1e-9);
            assertEquals(0,finalReport.inTransitMass(),1e-9);
        }
    }
}
