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
        var two=advance(restored,atlas,Set.of(M),List.of(transfer("edge-1",1,3)),2);
        assertEquals(3,two.transit().releasedTotalM3().doubleValue(),1e-9);
        assertEquals(0,two.transit().remainingM3().doubleValue(),1e-9);
        assertEquals(5,two.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
        var three=advance(two.checkpoint(),atlas,Set.of(M),
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
            advance(one.checkpoint(),atlas,Set.of(M),
                List.of(transfer("edge-1",2,9)),0));
        var correct=advance(one.checkpoint(),atlas,Set.of(M),
            List.of(transfer("edge-1",2,3)),0);
        assertEquals(3,correct.seasonal().water().water().state()
            .dischargedM3().get(M),1e-8);
    }
}
