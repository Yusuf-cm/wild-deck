package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedSeasonalEpochV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000;
    private static final GlobalHydrologyAddressV2.Sample M = at(5,2);
    private static final GlobalHydrologyAddressV2.Sample B = at(3,2);

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

    private static HydrologyTimedSeasonalEpochV2.Prepared advance(
            HydrologyCheckpointV2.Checkpoint previous,
            HydrologySphericalRouteAtlasV2 atlas,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Collection<HydrologyTimedTransitLedgerV2.Transfer> incoming,
            double rainM3) {
        var forcing = new HashMap<GlobalHydrologyAddressV2.Sample,
            HydrologySnowSoilStateV2.Forcing>();
        for (var sample:loaded) forcing.put(sample,weather(sample,
            sample.equals(M)?rainM3:0));
        // Persisted snow cell M must be explicitly forced even when unloaded.
        forcing.putIfAbsent(M,weather(M,0));
        return HydrologyTimedSeasonalEpochV2.prepareStreamingUniform(
            previous,atlas,forcing,Map.of(),loaded,incoming,2,86400);
    }

    private static HydrologyTimedTransitLedgerV2.Transfer transfer(
            String id,long due,double volume) {
        return new HydrologyTimedTransitLedgerV2.Transfer(id,
            HydrologyGlobalCatchmentsV2.coordinate(M),due,volume);
    }

    @Test void unloadedDueWaterWaitsAndLaterDischargesExactlyOnceWithRain() {
        var atlas=atlas();
        var first=initial(atlas);
        var one=advance(first,atlas,Set.of(B),List.of(transfer("edge-1",1,3)),0);
        assertEquals(1,one.checkpoint().epoch());
        assertEquals(3,one.transit().remainingM3().doubleValue(),1e-9);
        assertEquals(0,one.transit().releasedTotalM3().doubleValue(),1e-9);
        var restored=new HydrologyCheckpointV2.Checkpoint(one.checkpoint().epoch(),
            HydrologyTimedSeasonalEpochV2.encode(
                HydrologyTimedSeasonalEpochV2.decode(one.checkpoint().worldState())),
            one.checkpoint().inboxState());
        var two=advance(restored,atlas,Set.of(B,M),List.of(transfer("edge-1",1,3)),2);
        assertEquals(3,two.transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(0,two.transit().remainingM3().doubleValue(),1e-9);
        assertEquals(5,two.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        var three=advance(two.checkpoint(),atlas,Set.of(B,M),
            List.of(transfer("edge-1",1,3)),0);
        assertTrue(three.transit().releasedM3().isEmpty());
        assertEquals(5,three.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }

    @Test void durableCheckpointRejectsStaleWriterWithoutDuplicatingWater() throws IOException {
        var atlas=atlas();
        var first=initial(atlas);
        Path file=directory.resolve("timed.chk");
        HydrologyCheckpointStoreV2.save(file,first);
        var forcing=Map.of(M,weather(M,1));
        var committed=HydrologyTimedSeasonalEpochV2.commitStreamingUniform(
            file,first,atlas,forcing,Map.of(),Set.of(M),
            List.of(transfer("boundary-2",1,4)),2,86400);
        assertEquals(committed.checkpoint(),HydrologyCheckpointStoreV2.load(file));
        assertEquals(5,committed.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        assertThrows(IOException.class,()->
            HydrologyTimedSeasonalEpochV2.commitStreamingUniform(
                file,first,atlas,forcing,Map.of(),Set.of(M),
                List.of(transfer("boundary-2",1,4)),2,86400));
        assertEquals(committed.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

    @Test void rejectsWorldMismatchAndForgedFutureTransitSettlement() {
        var atlas=atlas();
        var first=initial(atlas);
        var decoded=HydrologyTimedSeasonalEpochV2.decode(first.worldState());
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTimedSeasonalEpochV2.State(decoded.seasonal(),
                HydrologyTimedTransitLedgerV2.encode(
                    HydrologyTimedTransitLedgerV2.empty("other"))));
        var future=advance(first,atlas,Set.of(M),
            List.of(transfer("future",2,4)),0);
        assertTrue(future.transit().releasedM3().isEmpty());
        assertEquals(4,future.transit().remainingM3().doubleValue(),1e-9);
        assertEquals(0,future.seasonal().water().water().state()
            .dischargedM3().getOrDefault(M,0.0),1e-9);
    }

    @Test void rejectsConflictingReplayBeforeCheckpointChange() {
        var atlas=atlas();
        var first=initial(atlas);
        var one=advance(first,atlas,Set.of(B),List.of(transfer("edge-1",2,3)),0);
        assertThrows(IllegalArgumentException.class,()->
            advance(one.checkpoint(),atlas,Set.of(B,M),
                List.of(transfer("edge-1",2,9)),0));
        var correct=advance(one.checkpoint(),atlas,Set.of(B,M),
            List.of(transfer("edge-1",2,3)),0);
        assertEquals(3,correct.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }

    @Test void transitAndAuthenticatedInboxReceiptConvergeWithoutDoubleRain() {
        var atlas=atlas();
        var first=initial(atlas);
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,at(4,2),M),7);
        var withInbox=new HydrologyCheckpointV2.Checkpoint(0,
            first.worldState(),HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var one=advance(withInbox,atlas,Set.of(M),
            List.of(transfer("timed-1",1,3)),2);
        assertEquals(12,one.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        var two=advance(one.checkpoint(),atlas,Set.of(M),
            List.of(transfer("timed-1",1,3)),0);
        assertEquals(12,two.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        assertTrue(two.transit().releasedM3().isEmpty());
        assertTrue(two.seasonal().water().water().claimedM3().isEmpty());
    }
    @Test void scheduledTransitMatchesUniformWithoutLakesAndSurvivesRestart() throws IOException {
        var atlas=atlas();
        var first=initial(atlas);
        var forcing=Map.of(M,weather(M,2));
        var incoming=List.of(transfer("scheduled-1",1,3));
        var uniform=HydrologyTimedSeasonalEpochV2.prepareStreamingUniform(
            first,atlas,forcing,Map.of(),Set.of(M),incoming,2,86400);
        Path file=directory.resolve("scheduled.chk");
        HydrologyCheckpointStoreV2.save(file,first);
        var scheduled=HydrologyTimedSeasonalEpochV2.commitStreamingScheduled(
            file,first,atlas,forcing,Map.of(),Map.of(),Set.of(M),incoming,86400);
        assertEquals(uniform.checkpoint(),scheduled.checkpoint());
        assertEquals(scheduled.checkpoint(),HydrologyCheckpointStoreV2.load(file));
        assertEquals(5,scheduled.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        assertThrows(IOException.class,()->HydrologyTimedSeasonalEpochV2.commitStreamingScheduled(
            file,first,atlas,forcing,Map.of(),Map.of(),Set.of(M),incoming,86400));
        assertEquals(scheduled.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

    @Test void scheduledLakeReceivesTimedWaterAsCarryoverNotRain() {
        var atlas=atlas();
        var base=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(B,0.0),Map.of())),new HydrologyEpochInboxV2());
        var bound=HydrologySeasonalTopologyEpochV2.bindFresh(base,atlas);
        var seasonal=HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
            bound,new HydrologySnowSoilStateV2.State(
                "world",Map.of(B,new HydrologySnowSoilStateV2.Cell(0,0))),RADIUS);
        var first=HydrologyTimedSeasonalEpochV2.bindFresh(seasonal);
        var basin=new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2);
        var lake=new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            basin,0,0,atlas.routeAt(B).downstream(),1);
        var schedules=Map.of(B,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0)));
        var timed=new HydrologyTimedTransitLedgerV2.Transfer(
            "lake-arrival",HydrologyGlobalCatchmentsV2.coordinate(B),1,3);
        var one=HydrologyTimedSeasonalEpochV2.prepareStreamingScheduled(
            first,atlas,Map.of(B,weather(B,0)),Map.of(B,lake),
            schedules,Set.of(B),List.of(timed),86400);
        assertEquals(3,one.transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(0,one.seasonal().snow().runoffVolume(),1e-9);
        assertEquals(2,one.seasonal().water().water().state()
            .lakes().lakeStorageM3().get(B),1e-8);
        assertEquals(1,one.seasonal().water().water().state()
            .lakes().pendingM3().values().stream()
            .mapToDouble(Double::doubleValue).sum(),1e-8);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedSeasonalEpochV2.prepareStreamingScheduled(
                first,atlas,Map.of(B,weather(B,0)),Map.of(B,lake),
                Map.of(),Set.of(B),List.of(timed),86400));
    }

    @Test void rejectsTransitTickInconsistentWithEmbeddedSeason() {
        var atlas=atlas();
        var first=initial(atlas);
        var state=HydrologyTimedSeasonalEpochV2.decode(first.worldState());
        var future=new HydrologyTimedTransitLedgerV2.State("world",1,Map.of(),Map.of());
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTimedSeasonalEpochV2.State(
                state.seasonal(),HydrologyTimedTransitLedgerV2.encode(future)));
        var one=advance(first,atlas,Set.of(M),List.of(),0);
        var later=HydrologyTimedSeasonalEpochV2.decode(one.checkpoint().worldState());
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTimedSeasonalEpochV2.State(
                later.seasonal(),HydrologyTimedTransitLedgerV2.encode(
                    HydrologyTimedTransitLedgerV2.empty("world"))));
    }

    @Test void rejectsPrecisionLossWhenTransitJoinsLargeExistingPendingWater() {
        var atlas=atlas();
        var first=initial(atlas);
        var outer=HydrologyTimedSeasonalEpochV2.decode(first.worldState());
        var snow=HydrologySeasonalSnowSoilWaterEpochV2.decode(outer.seasonal());
        var topology=HydrologySeasonalTopologyEpochV2.decode(snow.topologyState());
        var outlet=HydrologySeasonalOutletSettlementV2.decode(topology.waterState());
        var lake=outlet.lakes();
        var enlarged=new HydrologySeasonalLakeCarryoverStateV2.State(
            lake.world(),lake.season(),lake.lakeStorageM3(),Map.of(M,1e16));
        var rebuilt=HydrologySeasonalTopologyEpochV2.encode(
            new HydrologySeasonalTopologyEpochV2.Bound(topology.snapshotId(),
                HydrologySeasonalOutletSettlementV2.encode(
                    new HydrologySeasonalOutletSettlementV2.State(
                        enlarged,outlet.dischargedM3()))));
        var forgedSeasonal=HydrologySeasonalSnowSoilWaterEpochV2.encode(
            new HydrologySeasonalSnowSoilWaterEpochV2.State(
                snow.radiusMeters(),rebuilt,snow.snowState()));
        var candidate=new HydrologyCheckpointV2.Checkpoint(first.epoch(),
            HydrologyTimedSeasonalEpochV2.encode(
                new HydrologyTimedSeasonalEpochV2.State(
                    forgedSeasonal,outer.transit())),first.inboxState());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedSeasonalEpochV2.prepareStreamingUniform(
                candidate,atlas,Map.of(M,weather(M,0)),Map.of(),
                Set.of(M),List.of(transfer("tiny",1,1)),2,86400));
        // A sufficiently large transfer must still be accepted.
        var accepted=HydrologyTimedSeasonalEpochV2.prepareStreamingUniform(
            candidate,atlas,Map.of(M,weather(M,0)),Map.of(),
            Set.of(M),List.of(transfer("visible",1,100)),2,86400);
        assertEquals(100,accepted.transit().releasedTotalM3().doubleValue(),1e-9);
    }

    private static GlobalHydrologyAddressV2.Sample verifiedInlet(
            HydrologySphericalRouteAtlasV2 atlas,
            GlobalHydrologyAddressV2.Sample mouth) {
        return atlas.routes().stream().filter(node ->
            mouth.equals(node.downstream())).findFirst().orElseThrow().address();
    }

    @Test void verifiedBoundaryTransitPersistsExactlyOnceWithRainAndRestart() throws IOException {
        var routes=atlas();
        var source=verifiedInlet(routes,M);
        var incoming=new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            "verified-edge-1",source,M,1,3);
        var first=initial(routes);
        Path file=directory.resolve("verified-boundary.chk");
        HydrologyCheckpointStoreV2.save(file,first);
        var committed=HydrologyTimedSeasonalEpochV2.commitStreamingUniformVerified(
            file,first,routes,Map.of(M,weather(M,2)),Map.of(),Set.of(M),
            List.of(incoming,incoming),2,86400);
        assertEquals(3,committed.transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(5,committed.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        assertEquals(committed.checkpoint(),HydrologyCheckpointStoreV2.load(file));
        assertThrows(IOException.class,()->
            HydrologyTimedSeasonalEpochV2.commitStreamingUniformVerified(
                file,first,routes,Map.of(M,weather(M,2)),Map.of(),Set.of(M),
                List.of(incoming),2,86400));
        var resumed=HydrologyTimedSeasonalEpochV2.commitStreamingUniformVerified(
            file,HydrologyCheckpointStoreV2.load(file),routes,
            Map.of(M,weather(M,0)),Map.of(),Set.of(M),
            List.of(incoming),2,86400);
        assertTrue(resumed.transit().releasedM3().isEmpty());
        assertEquals(5,resumed.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }

    @Test void rejectsForgedNonDownstreamTerminalAndAliasedBoundaryTransfers() {
        var routes=atlas();
        var source=verifiedInlet(routes,M);
        var valid=new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            "boundary",source,M,1,3);
        var verified=HydrologyVerifiedTimedBoundaryV2.verify(routes,
            List.of(valid,valid));
        assertEquals(1,verified.size());
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(M),
            verified.get(0).destination());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyVerifiedTimedBoundaryV2.verify(routes,List.of(
                new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                    "forged",source,B,1,3))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyVerifiedTimedBoundaryV2.verify(routes,List.of(
                new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                    "terminal",M,source,1,3))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyVerifiedTimedBoundaryV2.verify(routes,List.of(
                new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                    "alias",at(8,2),M,1,3))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyVerifiedTimedBoundaryV2.verify(routes,List.of(
                valid,new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                    "boundary",source,M,2,3))));
    }

    @Test void verifiedScheduledLakeDeliveryUsesCarryoverRatherThanRain() {
        var routes=atlas();
        var source=verifiedInlet(routes,M);
        var first=initial(routes);
        var transfer=new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            "scheduled-verified",source,M,1,4);
        var verified=HydrologyTimedSeasonalEpochV2.prepareStreamingScheduledVerified(
            first,routes,Map.of(M,weather(M,1)),Map.of(),Map.of(),
            Set.of(M),List.of(transfer),86400);
        assertEquals(4,verified.transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(1,verified.seasonal().snow().runoffVolume(),1e-8);
        assertEquals(5,verified.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }

    @Test void verifiedBoundaryTransferWaitsAtUnloadedReceiverWithoutRestartingDeadline() {
        var routes=atlas();
        var source=verifiedInlet(routes,M);
        var transfer=new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            "verified-unloaded",source,M,1,6);
        var first=initial(routes);
        var one=HydrologyTimedSeasonalEpochV2.prepareStreamingUniformVerified(
            first,routes,Map.of(M,weather(M,0),B,weather(B,0)),
            Map.of(),Set.of(B),List.of(transfer),2,86400);
        assertEquals(6,one.transit().remainingM3().doubleValue(),1e-9);
        assertTrue(one.transit().releasedM3().isEmpty());
        var two=HydrologyTimedSeasonalEpochV2.prepareStreamingUniformVerified(
            one.checkpoint(),routes,Map.of(M,weather(M,0),B,weather(B,0)),
            Map.of(),Set.of(B),List.of(transfer),2,86400);
        assertEquals(6,two.transit().remainingM3().doubleValue(),1e-9);
        var three=HydrologyTimedSeasonalEpochV2.prepareStreamingUniformVerified(
            two.checkpoint(),routes,Map.of(M,weather(M,1),B,weather(B,0)),
            Map.of(),Set.of(B,M),List.of(transfer),2,86400);
        assertEquals(6,three.transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(7,three.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }

    @Test void verifiedBoundaryRoutingAcceptsLongitudeSeamAndPolarEdges() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++) for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        var seam=at(0,2);
        var seamAtlas=HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(seam)?0:10,Set.of(seam));
        assertEquals(seam,seamAtlas.routeAt(at(7,2)).downstream());
        assertEquals(1,HydrologyVerifiedTimedBoundaryV2.verify(seamAtlas,
            List.of(new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                "seam",at(7,2),seam,1,2))).size());
        var pole=at(0,0);
        var polarAtlas=HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(pole)?0:10,Set.of(pole));
        assertEquals(pole,polarAtlas.routeAt(at(7,1)).downstream());
        assertEquals(1,HydrologyVerifiedTimedBoundaryV2.verify(polarAtlas,
            List.of(new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                "pole",at(7,1),pole,1,2))).size());
    }

}
