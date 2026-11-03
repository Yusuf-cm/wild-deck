package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedBoundarySenderV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000;
    private static final GlobalHydrologyAddressV2.Sample M = at(5,2);

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static HydrologySphericalRouteAtlasV2 atlas(
            GlobalHydrologyAddressV2.Sample outlet) {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for (long y=1;y<4;y++) for (long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(outlet)?0:10,Set.of(outlet));
    }

    private static GlobalHydrologyAddressV2.Sample inlet(
            HydrologySphericalRouteAtlasV2 atlas,
            GlobalHydrologyAddressV2.Sample mouth) {
        return atlas.routes().stream().filter(r->mouth.equals(r.downstream()))
            .findFirst().orElseThrow().address();
    }

    private static HydrologyVerifiedTimedBoundaryV2.EdgeTransfer transfer(
            String id,GlobalHydrologyAddressV2.Sample source,
            GlobalHydrologyAddressV2.Sample destination,long due,double volume) {
        return new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            id,source,destination,due,volume);
    }

    private static HydrologyCheckpointV2.Checkpoint receiverInitial(
            HydrologySphericalRouteAtlasV2 atlas) {
        var raw=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(),Map.of())),new HydrologyEpochInboxV2());
        var bound=HydrologySeasonalTopologyEpochV2.bindFresh(raw,atlas);
        var seasonal=HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
            bound,new HydrologySnowSoilStateV2.State(
                "world",Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0))),RADIUS);
        return HydrologyTimedSeasonalEpochV2.bindFresh(seasonal);
    }

    private static HydrologySnowSoilStateV2.Forcing rain(double volume) {
        return HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            M,RADIUS,volume/HydrologySphericalCellAreaV2.areaAt(M,RADIUS),
            10,0,0,1,0);
    }

    @Test void durableSenderDebitAndReceiverSettlementConserveAndReplayOnce()
            throws IOException {
        var atlas=atlas(M);
        var source=inlet(atlas,M);
        var outgoing=transfer("sender-1",source,M,1,3);
        Path sender=directory.resolve("sender.chk");
        Path receiver=directory.resolve("receiver.chk");
        var first=HydrologyTimedBoundarySenderV2.initialize(sender,
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(source,10.0)));
        var debit=HydrologyTimedBoundarySenderV2.commit(
            sender,first,atlas,List.of(outgoing,outgoing));
        assertEquals(3,debit.step().debitedM3().doubleValue(),1e-9);
        assertEquals(7,debit.step().state().availableM3().get(source),1e-9);
        assertEquals(1,debit.step().newlyIssued().size());
        assertEquals(debit.checkpoint(),HydrologyCheckpointStoreV2.load(sender));
        var initial=receiverInitial(atlas);
        HydrologyCheckpointStoreV2.save(receiver,initial);
        var delivered=HydrologyTimedSeasonalEpochV2.commitStreamingUniformVerified(
            receiver,initial,atlas,Map.of(M,rain(2)),Map.of(),Set.of(M),
            debit.step().newlyIssued(),2,86400);
        assertEquals(3,delivered.transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(5,delivered.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        assertThrows(IOException.class,()->HydrologyTimedBoundarySenderV2.commit(
            sender,first,atlas,List.of(outgoing)));
        var replay=HydrologyTimedBoundarySenderV2.commit(sender,
            HydrologyCheckpointStoreV2.load(sender),atlas,List.of(outgoing));
        assertTrue(replay.step().newlyIssued().isEmpty());
        assertEquals(0,replay.step().debitedM3().doubleValue(),1e-9);
        assertEquals(7,replay.step().state().availableM3().get(source),1e-9);
        var receiverReplay=HydrologyTimedSeasonalEpochV2.commitStreamingUniformVerified(
            receiver,HydrologyCheckpointStoreV2.load(receiver),atlas,
            Map.of(M,rain(0)),Map.of(),Set.of(M),List.of(outgoing),2,86400);
        assertTrue(receiverReplay.transit().releasedM3().isEmpty());
        assertEquals(5,receiverReplay.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }

    @Test void senderJournalCodecRetainsEdgeProvenanceAndRejectsTampering() {
        var atlas=atlas(M);
        var source=inlet(atlas,M);
        var initial=HydrologyTimedBoundarySenderV2.empty("world",Map.of(source,8.0));
        var t=transfer("edge-1",source,M,3,2);
        var next=HydrologyTimedBoundarySenderV2.advance(initial,atlas,List.of(t)).state();
        var encoded=HydrologyTimedBoundarySenderV2.encode(next);
        assertEquals(next,HydrologyTimedBoundarySenderV2.decode(encoded));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.decode(
                encoded.replace("WD-TIMED-BOUNDARY-SENDER-1","BAD")));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.decode(encoded.replace("0x1.0p1","NaN")));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.advance(next,atlas,
                List.of(transfer("edge-1",source,M,4,2))));
    }

    @Test void duplicatePhysicalInventoryAndInsufficientDebitFailClosed() {
        var atlas=atlas(M);
        var source=inlet(atlas,M);
        var alias=new GlobalHydrologyAddressV2.Sample(
            2,2,source.x()*2,source.y()*2);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.empty("world",
                Map.of(source,3.0,alias,4.0)));
        var initial=HydrologyTimedBoundarySenderV2.empty(
            "world",Map.of(source,2.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.advance(initial,atlas,
                List.of(transfer("overdraft",source,M,1,3))));
        assertEquals(2,initial.availableM3().get(source),1e-9);
    }

    @Test void precisionSensitiveDebitRejectedRatherThanSilentlyLost() {
        var atlas=atlas(M);
        var source=inlet(atlas,M);
        var initial=HydrologyTimedBoundarySenderV2.empty(
            "world",Map.of(source,1e16));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.advance(initial,atlas,
                List.of(transfer("tiny",source,M,1,1))));
        var accepted=HydrologyTimedBoundarySenderV2.advance(initial,atlas,
            List.of(transfer("visible",source,M,1,100)));
        assertEquals(100,accepted.debitedM3().doubleValue(),1e-9);
    }

    @Test void journalRejectsChangedDrainageAndForgedEdges() {
        var atlas=atlas(M);
        var source=inlet(atlas,M);
        var first=HydrologyTimedBoundarySenderV2.empty(
            "world",Map.of(source,5.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.advance(first,atlas,
                List.of(transfer("forged",source,at(2,2),1,1))));
        var issued=HydrologyTimedBoundarySenderV2.advance(first,atlas,
            List.of(transfer("real",source,M,1,1))).state();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundarySenderV2.advance(issued,atlas(at(0,2)),List.of()));
    }

    @Test void geodesicPlannerComputesAbsoluteDueTickAndStableBatch() {
        var atlas=atlas(M);
        var source=inlet(atlas,M);
        var a=new HydrologyTimedBoundaryTravelV2.Journey(
            "a",source,M,10,3,2);
        var b=new HydrologyTimedBoundaryTravelV2.Journey(
            "b",source,M,10,2,1);
        var forward=HydrologyTimedBoundaryTravelV2.plan(
            atlas,List.of(a,b,a),RADIUS,100);
        var reverse=HydrologyTimedBoundaryTravelV2.plan(
            atlas,List.of(b,a),RADIUS,100);
        assertEquals(forward,reverse);
        assertEquals(2,forward.edges().size());
        double distance=RADIUS*HydrologySphericalTopologyV2.angularDistance(source,M);
        assertEquals(distance,forward.distancesMeters().get("a"),1e-9);
        long expected=10+Math.max(1L,(long)StrictMath.ceil(distance/2/100));
        assertEquals(expected,forward.edges().get(0).dueTick());
        assertTrue(forward.edges().get(1).dueTick()>=expected);
        assertEquals(2,HydrologyVerifiedTimedBoundaryV2.verify(
            atlas,forward.edges()).size());
    }

    @Test void geodesicPlannerRejectsInvalidClockVelocityRouteAndOverflow() {
        var atlas=atlas(M);
        var source=inlet(atlas,M);
        var journey=new HydrologyTimedBoundaryTravelV2.Journey(
            "a",source,M,1,1,1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryTravelV2.plan(atlas,List.of(journey),
                RADIUS,0));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTimedBoundaryTravelV2.Journey(
                "bad",source,M,1,1,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryTravelV2.plan(atlas,List.of(
                new HydrologyTimedBoundaryTravelV2.Journey(
                    "forged",source,at(2,2),1,1,1)),RADIUS,100));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryTravelV2.plan(atlas,List.of(
                new HydrologyTimedBoundaryTravelV2.Journey(
                    "overflow",source,M,Long.MAX_VALUE,1,1)),RADIUS,100));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryTravelV2.plan(atlas,List.of(journey,
                new HydrologyTimedBoundaryTravelV2.Journey(
                    "a",source,M,1,1,2)),RADIUS,100));
    }

    @Test void geodesicTravelHandlesLongitudeSeamAndPolarEdges() {
        var seam=at(0,2);
        var seamAtlas=atlas(seam);
        var polar=at(0,0);
        var polarAtlas=atlas(polar);
        var a=new HydrologyTimedBoundaryTravelV2.Journey(
            "seam",at(7,2),seam,0,1,2);
        var b=new HydrologyTimedBoundaryTravelV2.Journey(
            "pole",at(7,1),polar,0,1,2);
        assertEquals(1,HydrologyTimedBoundaryTravelV2.plan(
            seamAtlas,List.of(a),RADIUS,100000).edges().get(0).dueTick());
        assertEquals(1,HydrologyTimedBoundaryTravelV2.plan(
            polarAtlas,List.of(b),RADIUS,100000).edges().get(0).dueTick());
    }
}
