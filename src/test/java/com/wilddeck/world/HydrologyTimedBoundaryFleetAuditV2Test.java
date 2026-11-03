package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedBoundaryFleetAuditV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static HydrologySphericalRouteAtlasV2 atlas() {
        var sources = new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for (long y=1;y<4;y++) for (long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        var outlet = at(5,2);
        return HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(outlet)?0:10,Set.of(outlet));
    }
    private static HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge(
            String id,HydrologyFlowGraphV2.Node route,double volume) {
        return new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            id,route.address(),route.downstream(),3,volume);
    }
    private static HydrologyTimedTransitLedgerV2.Transfer receipt(
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer e) {
        return new HydrologyTimedTransitLedgerV2.Transfer(
            e.id(),HydrologyGlobalCatchmentsV2.coordinate(e.destination()),
            e.dueTick(),e.volumeM3());
    }
    private static void same(String expected,BigDecimal actual) {
        assertEquals(0,new BigDecimal(expected).compareTo(actual));
    }
    @Test void reconcilesTwoSendersPendingSettledAndMissingExactly() {
        var atlas=atlas();
        var routes=atlas.routes().stream().filter(r->!r.terminal()).limit(2).toList();
        assertEquals(2,routes.size());
        var first=edge("a",routes.get(0),2);
        var second=edge("b",routes.get(1),3);
        var s1=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(first.source(),10.0)),
            atlas,List.of(first)).state();
        var s2=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(second.source(),12.0)),
            atlas,List.of(second)).state();
        var receiver=new HydrologyTimedTransitLedgerV2.State("w",4,
            Map.of("a",receipt(first)),Map.of());
        var audit=HydrologyTimedBoundaryFleetAuditV2.audit(
            List.of(s2,s1),receiver,atlas,true);
        assertEquals(List.of(second),audit.missing());
        same("17.0",audit.availableM3());
        same("5.0",audit.issuedM3());
        same("2.0",audit.pendingM3());
        same("0",audit.settledM3());
        same("3.0",audit.unreceivedM3());
        same("22.0",audit.accountedM3());
        assertEquals(2,audit.senderCount());
        var received=HydrologyTimedTransitLedgerV2.advance(receiver,
            List.of(receipt(second)),4,Set.of(
                HydrologyGlobalCatchmentsV2.coordinate(first.destination())));
        var recovered=HydrologyTimedBoundaryFleetAuditV2.audit(
            List.of(s1,s2),received.state(),atlas,true);
        assertTrue(recovered.missing().isEmpty());
        same("22.0",recovered.accountedM3());
    }

    @Test void rejectsDuplicatePhysicalOwnershipEvenAcrossResolutions() {
        var atlas=atlas();
        var source=at(2,2);
        var higher=new GlobalHydrologyAddressV2.Sample(2,2,4,4);
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(source),
            HydrologyGlobalCatchmentsV2.coordinate(higher));
        var s1=HydrologyTimedBoundarySenderV2.empty("w",Map.of(source,4.0));
        var s2=HydrologyTimedBoundarySenderV2.empty("w",Map.of(higher,5.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.audit(List.of(s1,s2),
                HydrologyTimedTransitLedgerV2.empty("w"),atlas,false));
    }

    @Test void rejectsCrossSenderTransferIdCollisionEvenForIdenticalReceipts() {
        var atlas=atlas();
        var routes=atlas.routes().stream().filter(r->!r.terminal()).limit(2).toList();
        var a=edge("collision",routes.get(0),2);
        var b=edge("collision",routes.get(1),3);
        var s1=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(a.source(),10.0)),
            atlas,List.of(a)).state();
        var s2=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(b.source(),10.0)),
            atlas,List.of(b)).state();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.audit(List.of(s1,s2),
                HydrologyTimedTransitLedgerV2.empty("w"),atlas,false));
    }

    @Test void strictModeRejectsUnjournaledReceiverWaterButOpenModeExcludesIt() {
        var atlas=atlas();
        var foreign=new HydrologyTimedTransitLedgerV2.Transfer(
            "untracked",HydrologyGlobalCatchmentsV2.coordinate(at(5,2)),1,100);
        var receiver=new HydrologyTimedTransitLedgerV2.State("w",4,
            Map.of("untracked",foreign),Map.of());
        var sender=HydrologyTimedBoundarySenderV2.empty("w",Map.of(at(2,2),7.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.audit(
                List.of(sender),receiver,atlas,true));
        var audit=HydrologyTimedBoundaryFleetAuditV2.audit(
            List.of(sender),receiver,atlas,false);
        assertEquals(1,audit.foreignReceiverTransfers());
        same("7.0",audit.accountedM3());
    }

    @Test void rejectsForeignWorldAndChangedAtlas() {
        var atlas=atlas();
        var route=atlas.routes().stream().filter(r->at(5,2).equals(r.downstream())).findFirst().orElseThrow();
        var transfer=edge("a",route,2);
        var sender=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(transfer.source(),5.0)),
            atlas,List.of(transfer)).state();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.audit(List.of(sender),
                HydrologyTimedTransitLedgerV2.empty("different"),atlas,true));
        var changed=HydrologySphericalRouteAtlasV2.compile(
            atlas.routes().stream().map(r->new HydrologyGlobalSlopeRouterV2.Source(
                r.address(),0)).toList(),
            a->a.equals(at(0,2))?0:10,Set.of(at(0,2)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.audit(List.of(sender),
                HydrologyTimedTransitLedgerV2.empty("w"),changed,true));
    }

    private static HydrologyCheckpointV2.Checkpoint receiverInitial(
            HydrologySphericalRouteAtlasV2 atlas) {
        var raw=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "w",0,Map.of(),Map.of())),new HydrologyEpochInboxV2());
        var bound=HydrologySeasonalTopologyEpochV2.bindFresh(raw,atlas);
        var seasonal=HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
            bound,new HydrologySnowSoilStateV2.State(
                "w",Map.of(at(5,2),new HydrologySnowSoilStateV2.Cell(0,0))),1000);
        return HydrologyTimedSeasonalEpochV2.bindFresh(seasonal);
    }

    @Test void durableFleetRecoveryReadsTwoIndependentSenderJournals()
            throws IOException {
        var atlas=atlas();
        var routes=atlas.routes().stream().filter(r->!r.terminal()).limit(2).toList();
        var a=edge("a",routes.get(0),2);
        var b=edge("b",routes.get(1),3);
        Path one=directory.resolve("one.chk");
        Path two=directory.resolve("two.chk");
        Path receiver=directory.resolve("receiver.chk");
        var first=HydrologyTimedBoundarySenderV2.initialize(one,
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(a.source(),10.0)));
        var second=HydrologyTimedBoundarySenderV2.initialize(two,
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(b.source(),10.0)));
        HydrologyTimedBoundarySenderV2.commit(one,first,atlas,List.of(a));
        HydrologyTimedBoundarySenderV2.commit(two,second,atlas,List.of(b));
        HydrologyCheckpointStoreV2.save(receiver,receiverInitial(atlas));
        var audit=HydrologyTimedBoundaryFleetAuditV2.auditCheckpoints(
            List.of(two,one),receiver,atlas,true);
        assertEquals(List.of(a,b),audit.missing());
        same("20.0",audit.accountedM3());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.auditCheckpoints(
                List.of(one,one),receiver,atlas,true));
    }

    @Test void durableFleetRecoveryRejectsInconsistentCheckpointEpochs()
            throws IOException {
        var atlas=atlas();
        Path sender=directory.resolve("sender.chk");
        Path receiver=directory.resolve("receiver.chk");
        var initial=HydrologyTimedBoundarySenderV2.empty("w",Map.of(at(2,2),7.0));
        HydrologyCheckpointStoreV2.save(sender,HydrologyCheckpointV2.capture(
            2,HydrologyTimedBoundarySenderV2.encode(initial),
            new HydrologyEpochInboxV2()));
        HydrologyCheckpointStoreV2.save(receiver,receiverInitial(atlas));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.auditCheckpoints(
                List.of(sender),receiver,atlas,true));
        Path goodSender=directory.resolve("good.chk");
        HydrologyTimedBoundarySenderV2.initialize(goodSender,initial);
        Path badReceiver=directory.resolve("badreceiver.chk");
        var valid=receiverInitial(atlas);
        HydrologyCheckpointStoreV2.save(badReceiver,HydrologyCheckpointV2.capture(
            2,valid.worldState(),new HydrologyEpochInboxV2()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.auditCheckpoints(
                List.of(goodSender),badReceiver,atlas,true));
    }

    @Test void hardLinkedSenderJournalCannotBeCountedTwice()
            throws IOException {
        var atlas=atlas();
        Path sender=directory.resolve("sender-original.chk");
        Path alias=directory.resolve("sender-hardlink.chk");
        Path receiver=directory.resolve("receiver-hardlink.chk");
        HydrologyTimedBoundarySenderV2.initialize(sender,
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(at(2,2),7.0)));
        Files.createLink(alias,sender);
        HydrologyCheckpointStoreV2.save(receiver,receiverInitial(atlas));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetAuditV2.auditCheckpoints(
                List.of(sender,alias),receiver,atlas,true));
        assertEquals(0,HydrologyTimedBoundaryFleetAuditV2.auditCheckpoints(
            List.of(sender),receiver,atlas,true).missing().size());
    }
}
