package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedBoundaryNetworkAuditV2Test {
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
        return HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(at(5,2))?0:10,Set.of(at(5,2)));
    }

    private static List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> edges(
            HydrologySphericalRouteAtlasV2 atlas) {
        var selected = new ArrayList<HydrologyFlowGraphV2.Node>();
        var destinations = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var sources = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var node : atlas.routes()) {
            if (node.terminal()) continue;
            var source = HydrologyGlobalCatchmentsV2.coordinate(node.address());
            var destination = HydrologyGlobalCatchmentsV2.coordinate(node.downstream());
            if (destinations.contains(destination) || sources.contains(source)) continue;
            selected.add(node);
            destinations.add(destination);
            sources.add(source);
            if (selected.size() == 2) break;
        }
        assertEquals(2,selected.size());
        return List.of(
            new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                "edge-a",selected.get(0).address(),selected.get(0).downstream(),3,2),
            new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                "edge-b",selected.get(1).address(),selected.get(1).downstream(),3,3));
    }

    private static HydrologyTimedBoundarySenderV2.State sender(
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge) {
        return HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(edge.source(),10.0)),
            atlas,List.of(edge)).state();
    }

    private static HydrologyTimedTransitLedgerV2.Transfer transfer(
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge) {
        return new HydrologyTimedTransitLedgerV2.Transfer(
            edge.id(),HydrologyGlobalCatchmentsV2.coordinate(edge.destination()),
            edge.dueTick(),edge.volumeM3());
    }

    private static HydrologyTimedBoundaryNetworkAuditV2.Receiver receiver(
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge,
            boolean settled) {
        var t = transfer(edge);
        return new HydrologyTimedBoundaryNetworkAuditV2.Receiver(
            Set.of(edge.destination()),
            new HydrologyTimedTransitLedgerV2.State("w",4,
                settled?Map.of():Map.of(t.id(),t),
                settled?Map.of(t.id(),t):Map.of()));
    }

    @Test void auditsMultipleReceiversAndConservesExactBoundaryVolume() {
        var atlas = atlas();
        var edges = edges(atlas);
        var a = edges.get(0);
        var b = edges.get(1);
        var audit = HydrologyTimedBoundaryNetworkAuditV2.audit(
            List.of(sender(atlas,b),sender(atlas,a)),
            Map.of("b",receiver(b,true),"a",receiver(a,false)),atlas,true);
        assertEquals(2,audit.senders());
        assertEquals(2,audit.receivers());
        assertEquals(1,audit.pending());
        assertEquals(1,audit.settled());
        assertEquals(0,audit.missing());
        assertEquals(0,audit.foreign());
        assertEquals(0,new BigDecimal("15.0").compareTo(audit.availableM3()));
        assertEquals(0,new BigDecimal("5.0").compareTo(audit.issuedM3()));
        assertEquals(0,new BigDecimal("2.0").compareTo(audit.pendingM3()));
        assertEquals(0,new BigDecimal("3.0").compareTo(audit.settledM3()));
        assertFalse(audit.quiescent());
    }

    @Test void missingAndUnassignedTransfersBlockTopologyChange() throws IOException {
        var atlas = atlas();
        var a = edges(atlas).get(0);
        var sender = sender(atlas,a);
        var empty = new HydrologyTimedBoundaryNetworkAuditV2.Receiver(
            Set.of(),HydrologyTimedTransitLedgerV2.empty("w"));
        var audit = HydrologyTimedBoundaryNetworkAuditV2.audit(
            List.of(sender),Map.of("receiver",empty),atlas,true);
        assertEquals(1,audit.missing());
        assertEquals(1,audit.unassigned());
        assertEquals(0,new BigDecimal("2.0").compareTo(audit.missingM3()));
        assertFalse(audit.quiescent());
    }

    @Test void rejectsCrossResolutionOwnershipAndWrongReceiver() {
        var atlas = atlas();
        var a = edges(atlas).get(0);
        var coarse = at(2,2);
        var fine = new GlobalHydrologyAddressV2.Sample(2,2,4,4);
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(coarse),
            HydrologyGlobalCatchmentsV2.coordinate(fine));
        var first = new HydrologyTimedBoundaryNetworkAuditV2.Receiver(
            Set.of(coarse),HydrologyTimedTransitLedgerV2.empty("w"));
        var second = new HydrologyTimedBoundaryNetworkAuditV2.Receiver(
            Set.of(fine),HydrologyTimedTransitLedgerV2.empty("w"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkAuditV2.audit(List.of(),
                Map.of("first",first,"second",second),atlas,true));
        var wrong = new HydrologyTimedBoundaryNetworkAuditV2.Receiver(
            Set.of(at(0,0)),
            new HydrologyTimedTransitLedgerV2.State("w",4,
                Map.of(a.id(),transfer(a)),Map.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkAuditV2.audit(
                List.of(sender(atlas,a)),Map.of("wrong",wrong),atlas,true));
    }

    @Test void strictModeRejectsForeignReceiptsAndDuplicateIds() {
        var atlas = atlas();
        var edges = edges(atlas);
        var a = edges.get(0);
        var b = edges.get(1);
        var foreign = HydrologyTimedBoundaryNetworkAuditV2.audit(
            List.of(sender(atlas,a)),Map.of("a",receiver(a,true),
                "b",receiver(b,false)),atlas,false);
        assertEquals(1,foreign.foreign());
        assertFalse(foreign.quiescent());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkAuditV2.audit(
                List.of(sender(atlas,a)),Map.of("a",receiver(a,true),
                    "b",receiver(b,false)),atlas,true));
        var duplicate = new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            a.id(),b.source(),b.destination(),b.dueTick(),b.volumeM3());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkAuditV2.audit(
                List.of(sender(atlas,a),sender(atlas,duplicate)),
                Map.of("a",receiver(a,true)),atlas,true));
    }

    private static HydrologyCheckpointV2.Checkpoint receiverInitial(
            HydrologySphericalRouteAtlasV2 atlas,double snowDepth) {
        var raw = HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "w",0,Map.of(),Map.of())),new HydrologyEpochInboxV2());
        var bound = HydrologySeasonalTopologyEpochV2.bindFresh(raw,atlas);
        var seasonal = HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
            bound,new HydrologySnowSoilStateV2.State(
                "w",Map.of(at(5,2),new HydrologySnowSoilStateV2.Cell(snowDepth,0))),
            1000);
        return HydrologyTimedSeasonalEpochV2.bindFresh(seasonal);
    }

    @Test void durableDryGateChecksStorageAndCheckpointAliases() throws IOException {
        var atlas = atlas();
        var sender = directory.resolve("sender.chk");
        var alias = directory.resolve("sender-alias.chk");
        var receiver = directory.resolve("receiver.chk");
        HydrologyTimedBoundarySenderV2.initialize(sender,
            HydrologyTimedBoundarySenderV2.empty("w",Map.of(at(2,2),7.0)));
        HydrologyCheckpointStoreV2.save(receiver,receiverInitial(atlas,0));
        var files = Map.of("sender",sender);
        var receivers = Map.of("receiver",
            new HydrologyTimedBoundaryNetworkAuditV2.ReceiverFile(
                receiver,Set.of(at(5,2))));
        assertTrue(HydrologyTimedBoundaryNetworkAuditV2.requireDryForTopologyChange(
            files,receivers,atlas).quiescent());
        Files.createLink(alias,sender);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkAuditV2.auditCheckpoints(
                Map.of("sender",sender,"alias",alias),receivers,atlas,true));
        var wetReceiver = directory.resolve("wet-receiver.chk");
        HydrologyCheckpointStoreV2.save(wetReceiver,receiverInitial(atlas,1));
        var wetReceivers = Map.of("receiver",
            new HydrologyTimedBoundaryNetworkAuditV2.ReceiverFile(
                wetReceiver,Set.of(at(5,2))));
        assertThrows(IllegalStateException.class,()->
            HydrologyTimedBoundaryNetworkAuditV2.requireDryForTopologyChange(
                files,wetReceivers,atlas));
    }

    @Test void receiverCheckpointEpochForgeryIsRejected() throws IOException {
        var atlas = atlas();
        var receiver = directory.resolve("receiver-epoch.chk");
        var valid = receiverInitial(atlas,0);
        var forged = new HydrologyCheckpointV2.Checkpoint(2,
            valid.worldState(),valid.inboxState());
        HydrologyCheckpointStoreV2.save(receiver,forged);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkAuditV2.auditCheckpoints(
                Map.of(),Map.of("r",
                    new HydrologyTimedBoundaryNetworkAuditV2.ReceiverFile(
                        receiver,Set.of(at(5,2)))),atlas,true));
    }
}
