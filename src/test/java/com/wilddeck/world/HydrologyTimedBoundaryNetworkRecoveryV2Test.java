package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedBoundaryNetworkRecoveryV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000;

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
        for (var node : atlas.routes()) {
            if (node.terminal()) continue;
            if (!destinations.add(HydrologyGlobalCatchmentsV2.coordinate(node.downstream())))
                continue;
            selected.add(node);
            if (selected.size()==2) break;
        }
        assertEquals(2,selected.size());
        return List.of(
            new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                "water-z",selected.get(0).address(),selected.get(0).downstream(),1,2),
            new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                "water-a",selected.get(1).address(),selected.get(1).downstream(),1,3));
    }

    private static HydrologyTimedBoundarySenderV2.State sender(
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge) {
        return HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty(
                "world",Map.of(edge.source(),10.0)),atlas,List.of(edge)).state();
    }

    private static HydrologyTimedBoundaryNetworkAuditV2.Receiver receiver(
            GlobalHydrologyAddressV2.Sample destination,
            HydrologyTimedTransitLedgerV2.State transit) {
        return new HydrologyTimedBoundaryNetworkAuditV2.Receiver(
            Set.of(destination),transit);
    }

    private static HydrologyTimedTransitLedgerV2.Transfer transit(
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge) {
        return new HydrologyTimedTransitLedgerV2.Transfer(
            edge.id(),HydrologyGlobalCatchmentsV2.coordinate(edge.destination()),
            edge.dueTick(),edge.volumeM3());
    }

    private static HydrologyCheckpointV2.Checkpoint initial(
            HydrologySphericalRouteAtlasV2 atlas,
            GlobalHydrologyAddressV2.Sample destination) {
        var raw = HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(),Map.of())),new HydrologyEpochInboxV2());
        var bound = HydrologySeasonalTopologyEpochV2.bindFresh(raw,atlas);
        var seasonal = HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
            bound,new HydrologySnowSoilStateV2.State(
                "world",Map.of(destination,new HydrologySnowSoilStateV2.Cell(0,0))),
            RADIUS);
        return HydrologyTimedSeasonalEpochV2.bindFresh(seasonal);
    }

    private static HydrologySnowSoilStateV2.Forcing dry(
            GlobalHydrologyAddressV2.Sample sample) {
        return HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            sample,RADIUS,0,10,0,0,1,0);
    }

    @Test void plansMissingWaterForDistinctPhysicalReceiversWithoutDuplication() {
        var atlas=atlas();
        var edges=edges(atlas);
        var senders=List.of(sender(atlas,edges.get(1)),sender(atlas,edges.get(0)));
        var receivers=Map.of(
            "east",receiver(edges.get(0).destination(),HydrologyTimedTransitLedgerV2.empty("world")),
            "west",receiver(edges.get(1).destination(),HydrologyTimedTransitLedgerV2.empty("world")));
        var plan=HydrologyTimedBoundaryNetworkRecoveryV2.plan(senders,receivers,atlas);
        assertEquals(2,plan.audit().missing());
        assertEquals(0,new BigDecimal("5.0").compareTo(plan.audit().missingM3()));
        assertEquals(List.of(edges.get(0)),plan.missingByReceiver().get("east"));
        assertEquals(List.of(edges.get(1)),plan.missingByReceiver().get("west"));
        assertTrue(plan.expectedCheckpoints().isEmpty());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkRecoveryV2.prepareUniform(
                plan,"east",atlas,Map.of(),Map.of(),Set.of(),2,86400));
    }

    @Test void excludesAlreadyPendingOrSettledIdsAndRejectsUnassignedDestinations() {
        var atlas=atlas();
        var edges=edges(atlas);
        var a=edges.get(0);
        var b=edges.get(1);
        var pending=new HydrologyTimedTransitLedgerV2.State("world",0,
            Map.of(a.id(),transit(a)),Map.of());
        var plan=HydrologyTimedBoundaryNetworkRecoveryV2.plan(
            List.of(sender(atlas,a),sender(atlas,b)),
            Map.of("east",receiver(a.destination(),pending),
                   "west",receiver(b.destination(),HydrologyTimedTransitLedgerV2.empty("world"))),
            atlas);
        assertEquals(1,plan.audit().pending());
        assertEquals(1,plan.audit().missing());
        assertTrue(plan.missingByReceiver().get("east").isEmpty());
        assertEquals(List.of(b),plan.missingByReceiver().get("west"));
        assertThrows(IllegalStateException.class,()->
            HydrologyTimedBoundaryNetworkRecoveryV2.plan(
                List.of(sender(atlas,a),sender(atlas,b)),
                Map.of("east",receiver(a.destination(),pending)),atlas));
    }

    @Test void durableRecoveryPreparesAndCommitsOnlySelectedReceiver() throws IOException {
        var atlas=atlas();
        var edges=edges(atlas);
        var senders=new TreeMap<String,Path>();
        var receivers=new TreeMap<String,HydrologyTimedBoundaryNetworkAuditV2.ReceiverFile>();
        for (int i=0;i<2;i++) {
            var edge=edges.get(i);
            var senderFile=directory.resolve("sender-"+i+".chk");
            var first=HydrologyTimedBoundarySenderV2.initialize(senderFile,
                HydrologyTimedBoundarySenderV2.empty(
                    "world",Map.of(edge.source(),10.0)));
            HydrologyTimedBoundarySenderV2.commit(senderFile,first,atlas,List.of(edge));
            senders.put("s"+i,senderFile);
            var receiverFile=directory.resolve("receiver-"+i+".chk");
            HydrologyCheckpointStoreV2.save(receiverFile,initial(atlas,edge.destination()));
            receivers.put("r"+i,new HydrologyTimedBoundaryNetworkAuditV2.ReceiverFile(
                receiverFile,Set.of(edge.destination())));
        }
        var plan=HydrologyTimedBoundaryNetworkRecoveryV2.planCheckpoints(
            senders,receivers,atlas);
        assertEquals(2,plan.audit().missing());
        var destination=edges.get(0).destination();
        var prepared=HydrologyTimedBoundaryNetworkRecoveryV2.prepareUniform(
            plan,"r0",atlas,Map.of(destination,dry(destination)),Map.of(),
            Set.of(destination),2,86400);
        assertEquals(2,prepared.transit().releasedTotalM3().doubleValue(),1e-9);
        var receiverFile=receivers.get("r0").checkpoint();
        var untouched=HydrologyCheckpointStoreV2.load(receivers.get("r1").checkpoint());
        HydrologyCheckpointStoreV2.saveNext(
            receiverFile,plan.expectedCheckpoints().get("r0"),prepared.checkpoint());
        assertEquals(untouched,HydrologyCheckpointStoreV2.load(
            receivers.get("r1").checkpoint()));
        assertThrows(IOException.class,()->
            HydrologyCheckpointStoreV2.saveNext(
                receiverFile,plan.expectedCheckpoints().get("r0"),prepared.checkpoint()));
        var after=HydrologyTimedBoundaryNetworkRecoveryV2.planCheckpoints(
            senders,receivers,atlas);
        assertEquals(1,after.audit().missing());
        assertEquals(1,after.audit().settled());
        assertTrue(after.missingByReceiver().get("r0").isEmpty());
        assertEquals(List.of(edges.get(1)),after.missingByReceiver().get("r1"));
        assertEquals(0,new BigDecimal("3.0").compareTo(after.audit().missingM3()));
    }

    @Test void scheduledPreparationAndCheckpointAliasFailClosed() throws IOException {
        var atlas=atlas();
        var edge=edges(atlas).get(0);
        var senderFile=directory.resolve("sender.chk");
        var first=HydrologyTimedBoundarySenderV2.initialize(senderFile,
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(edge.source(),10.0)));
        HydrologyTimedBoundarySenderV2.commit(senderFile,first,atlas,List.of(edge));
        var receiverFile=directory.resolve("receiver.chk");
        HydrologyCheckpointStoreV2.save(receiverFile,initial(atlas,edge.destination()));
        var receivers=Map.of("r",new HydrologyTimedBoundaryNetworkAuditV2.ReceiverFile(
            receiverFile,Set.of(edge.destination())));
        var plan=HydrologyTimedBoundaryNetworkRecoveryV2.planCheckpoints(
            Map.of("s",senderFile),receivers,atlas);
        var prepared=HydrologyTimedBoundaryNetworkRecoveryV2.prepareScheduled(
            plan,"r",atlas,Map.of(edge.destination(),dry(edge.destination())),
            Map.of(),Map.of(),Set.of(edge.destination()),86400);
        assertEquals(2,prepared.transit().releasedTotalM3().doubleValue(),1e-9);
        var alias=directory.resolve("alias.chk");
        Files.createLink(alias,senderFile);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryNetworkRecoveryV2.planCheckpoints(
                Map.of("s",senderFile,"alias",alias),receivers,atlas));
        assertEquals(first.epoch()+1,
            HydrologyCheckpointStoreV2.load(senderFile).epoch());
        assertEquals(0,HydrologyCheckpointStoreV2.load(receiverFile).epoch());
    }
}
