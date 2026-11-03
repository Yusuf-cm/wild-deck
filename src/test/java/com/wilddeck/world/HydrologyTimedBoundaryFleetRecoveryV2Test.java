package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedBoundaryFleetRecoveryV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample M = at(5,2);
    private static final GlobalHydrologyAddressV2.Sample B = at(3,2);

    private static HydrologySphericalRouteAtlasV2 atlas() {
        var sources = new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for (long y=1;y<4;y++) for (long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(M)?0:10,Set.of(M));
    }

    private static HydrologyCheckpointV2.Checkpoint initial(
            HydrologySphericalRouteAtlasV2 atlas) {
        var raw = HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(),Map.of())),new HydrologyEpochInboxV2());
        var bound = HydrologySeasonalTopologyEpochV2.bindFresh(raw,atlas);
        var seasonal = HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
            bound,new HydrologySnowSoilStateV2.State("world",Map.of(
                M,new HydrologySnowSoilStateV2.Cell(0,0))),RADIUS);
        return HydrologyTimedSeasonalEpochV2.bindFresh(seasonal);
    }

    private static HydrologySnowSoilStateV2.Forcing weather(
            GlobalHydrologyAddressV2.Sample sample,double precipitationM3) {
        double area=HydrologySphericalCellAreaV2.areaAt(sample,RADIUS);
        return HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            sample,RADIUS,precipitationM3/area,10,0,0,1,0);
    }

    private static HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge(
            String id, HydrologyFlowGraphV2.Node node,
            long dueTick,double volume) {
        return new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            id,node.address(),node.downstream(),dueTick,volume);
    }

    private List<Path> senders(HydrologySphericalRouteAtlasV2 atlas,
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer... edges)
            throws IOException {
        var paths=new ArrayList<Path>();
        for (int i=0;i<edges.length;i++) {
            var e=edges[i];
            var file=directory.resolve("sender-"+i+".chk");
            var initial=HydrologyTimedBoundarySenderV2.empty(
                "world",Map.of(e.source(),10.0));
            var checkpoint=HydrologyTimedBoundarySenderV2.initialize(file,initial);
            HydrologyTimedBoundarySenderV2.commit(file,checkpoint,atlas,List.of(e));
            paths.add(file);
        }
        return paths;
    }

    @Test void recoversTwoSendersAcrossUnloadedSeasonsAndDoesNotDoubleRain()
            throws IOException {
        var atlas=atlas();
        var routes=atlas.routes().stream().filter(n->!n.terminal()).limit(2).toList();
        var a=edge("fleet-a",routes.get(0),1,2);
        var b=edge("fleet-b",routes.get(1),2,3);
        var senders=senders(atlas,a,b);
        var receiver=directory.resolve("receiver.chk");
        var first=initial(atlas);
        HydrologyCheckpointStoreV2.save(receiver,first);

        var one=HydrologyTimedBoundaryFleetRecoveryV2.commitUniform(
            senders,receiver,first,atlas,
            Map.of(M,weather(M,0),B,weather(B,0)),Map.of(),Set.of(B),
            2,86400,true);
        assertEquals(List.of(a,b),one.before().missing());
        assertEquals(5,one.prepared().transit().remainingM3().doubleValue(),1e-9);
        assertEquals(0,one.prepared().transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(one.prepared().checkpoint(),HydrologyCheckpointStoreV2.load(receiver));

        var two=HydrologyTimedBoundaryFleetRecoveryV2.commitUniform(
            senders,receiver,one.prepared().checkpoint(),atlas,
            Map.of(M,weather(M,1),B,weather(B,0)),Map.of(),Set.of(M,B),
            2,86400,true);
        assertTrue(two.before().missing().isEmpty());
        assertEquals(5,two.prepared().transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(6,two.prepared().seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);

        var three=HydrologyTimedBoundaryFleetRecoveryV2.commitUniform(
            senders,receiver,two.prepared().checkpoint(),atlas,
            Map.of(M,weather(M,0),B,weather(B,0)),Map.of(),Set.of(M,B),
            2,86400,true);
        assertTrue(three.prepared().transit().releasedM3().isEmpty());
        assertEquals(6,three.prepared().seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }

    @Test void rejectsForeignReceiverIdsBeforeAnyWrite() throws IOException {
        var atlas=atlas();
        var receiver=directory.resolve("foreign.chk");
        var first=initial(atlas);
        var outer=HydrologyTimedSeasonalEpochV2.decode(first.worldState());
        var foreign=new HydrologyTimedTransitLedgerV2.Transfer(
            "foreign",HydrologyGlobalCatchmentsV2.coordinate(M),1,7);
        var queue=new HydrologyTimedTransitLedgerV2.State(
            "world",0,Map.of("foreign",foreign),Map.of());
        var forged=new HydrologyCheckpointV2.Checkpoint(0,
            HydrologyTimedSeasonalEpochV2.encode(
                new HydrologyTimedSeasonalEpochV2.State(
                    outer.seasonal(),HydrologyTimedTransitLedgerV2.encode(queue))),
            first.inboxState());
        HydrologyCheckpointStoreV2.save(receiver,forged);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetRecoveryV2.commitUniform(
                List.of(),receiver,forged,atlas,Map.of(M,weather(M,0)),
                Map.of(),Set.of(M),2,86400,true));
        assertEquals(forged,HydrologyCheckpointStoreV2.load(receiver));
    }

    @Test void scheduledRecoveryAndStaleReceiverFailClosed() throws IOException {
        var atlas=atlas();
        var route=atlas.routes().stream().filter(n->M.equals(n.downstream()))
            .findFirst().orElseThrow();
        var edge=edge("scheduled-fleet",route,1,4);
        var senders=senders(atlas,edge);
        var receiver=directory.resolve("scheduled.chk");
        var first=initial(atlas);
        HydrologyCheckpointStoreV2.save(receiver,first);
        var committed=HydrologyTimedBoundaryFleetRecoveryV2.commitScheduled(
            senders,receiver,first,atlas,Map.of(M,weather(M,2)),
            Map.of(),Map.of(),Set.of(M),86400,true);
        assertEquals(6,committed.prepared().seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        assertEquals(committed.prepared().checkpoint(),HydrologyCheckpointStoreV2.load(receiver));
        assertThrows(IOException.class,()->
            HydrologyTimedBoundaryFleetRecoveryV2.commitScheduled(
                senders,receiver,first,atlas,Map.of(M,weather(M,2)),
                Map.of(),Map.of(),Set.of(M),86400,true));
        assertEquals(committed.prepared().checkpoint(),HydrologyCheckpointStoreV2.load(receiver));
    }

    @Test void crossResolutionDuplicateSenderOwnershipRejectsRecovery()
            throws IOException {
        var atlas=atlas();
        var receiver=directory.resolve("ownership.chk");
        var first=initial(atlas);
        HydrologyCheckpointStoreV2.save(receiver,first);
        var s1=directory.resolve("one.chk");
        var s2=directory.resolve("two.chk");
        HydrologyTimedBoundarySenderV2.initialize(s1,
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(at(2,2),5.0)));
        HydrologyTimedBoundarySenderV2.initialize(s2,
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(
                new GlobalHydrologyAddressV2.Sample(2,2,4,4),6.0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryFleetRecoveryV2.commitUniform(
                List.of(s1,s2),receiver,first,atlas,
                Map.of(M,weather(M,0)),Map.of(),Set.of(M),2,86400,true));
        assertEquals(first,HydrologyCheckpointStoreV2.load(receiver));
    }
}
