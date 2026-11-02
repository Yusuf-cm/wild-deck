package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalSnowSoilWaterEpochV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000.0;
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
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,Double> lakes,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Cell> cells) {
        var old = HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,lakes,Map.of())),new HydrologyEpochInboxV2());
        var bound = HydrologySeasonalTopologyEpochV2.bindFresh(old,atlas);
        return HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
            bound,new HydrologySnowSoilStateV2.State("world",cells),RADIUS);
    }

    private static HydrologySnowSoilStateV2.Forcing weather(
            GlobalHydrologyAddressV2.Sample at,
            double precipitationM3,double temperatureC,double meltDepth) {
        double area = HydrologySphericalCellAreaV2.areaAt(at,RADIUS);
        return HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            at,RADIUS,precipitationM3/area,temperatureC,
            0,0,1,meltDepth);
    }

    private static HydrologySeasonalSnowSoilWaterEpochV2.Prepared advance(
            HydrologyCheckpointV2.Checkpoint old,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes) {
        return HydrologySeasonalSnowSoilWaterEpochV2.prepareUniform(
            old,atlas,forcing,lakes,forcing.keySet(),2,86400);
    }

    @Test void precipitationBecomesExactlyOneOceanSourceAndIsPersisted() {
        var atlas = atlas();
        var first = initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var result = advance(first,atlas,Map.of(M,weather(M,2,10,0)),Map.of());
        assertEquals(1,result.checkpoint().epoch());
        assertEquals(2,result.snow().runoffVolume(),1e-8);
        assertEquals(2,result.water().water().state().dischargedM3().get(M),1e-8);
        assertEquals(0,result.snow().storedVolume(),1e-8);
        assertEquals(result.checkpoint().worldState(),
            HydrologySeasonalSnowSoilWaterEpochV2.encode(
                HydrologySeasonalSnowSoilWaterEpochV2.decode(
                    result.checkpoint().worldState())));
    }

    @Test void frozenPrecipitationIsStoredAndLaterMeltsWithoutSecondRainfall() {
        var atlas = atlas();
        var first = initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var cold = advance(first,atlas,Map.of(M,weather(M,2,-5,0)),Map.of());
        assertEquals(0,cold.snow().runoffVolume(),1e-9);
        assertEquals(2,cold.snow().storedVolume(),1e-8);
        assertTrue(cold.water().water().state().dischargedM3().isEmpty() ||
            cold.water().water().state().dischargedM3().getOrDefault(M,0.0)==0.0);
        double area = HydrologySphericalCellAreaV2.areaAt(M,RADIUS);
        var warm = advance(cold.checkpoint(),atlas,
            Map.of(M,weather(M,0,5,2/area/5)),Map.of());
        assertEquals(2,warm.snow().runoffVolume(),1e-8);
        assertEquals(2,warm.water().water().state().dischargedM3().get(M),1e-8);
        assertEquals(0,warm.snow().storedVolume(),1e-8);
    }

    @Test void uniformAndChronologicalNoLakeAdvancementMatch() {
        var atlas = atlas();
        var first = initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var forcing = Map.of(M,weather(M,1,10,0));
        var uniform = advance(first,atlas,forcing,Map.of());
        var scheduled = HydrologySeasonalSnowSoilWaterEpochV2.prepareScheduled(
            first,atlas,forcing,Map.of(),Map.of(),Set.of(M),86400);
        assertEquals(uniform.checkpoint(),scheduled.checkpoint());
    }

    @Test void lakeStoresRunoffThenSpillsWithoutDuplicatingSnowSoilWater() {
        var atlas = atlas();
        var first = initial(atlas,Map.of(B,0.0),
            Map.of(B,new HydrologySnowSoilStateV2.Cell(0,0)));
        var basin = new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2);
        var lake = new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            basin,0,0,atlas.routeAt(B).downstream(),1);
        var stored = advance(first,atlas,Map.of(B,weather(B,1,10,0)),Map.of(B,lake));
        assertEquals(1,stored.water().water().state().lakes().lakeStorageM3().get(B),1e-8);
        var secondLake = new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            basin,1,0,atlas.routeAt(B).downstream(),1);
        var spilled = advance(stored.checkpoint(),atlas,
            Map.of(B,weather(B,3,10,0)),Map.of(B,secondLake));
        assertEquals(2,spilled.water().water().state().lakes().lakeStorageM3().get(B),1e-8);
        assertEquals(2,spilled.water().water().state().lakes().pendingM3()
            .values().stream().mapToDouble(Double::doubleValue).sum(),1e-8);
    }

    @Test void durableReceiptAndSnowRunoffConvergeOnceAcrossRestart() throws IOException {
        var atlas = atlas();
        var first = initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,at(4,2),M),3);
        var received = new HydrologyCheckpointV2.Checkpoint(
            0,first.worldState(),HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var file = directory.resolve("snow-water.chk");
        HydrologyCheckpointStoreV2.save(file,received);
        var one = HydrologySeasonalSnowSoilWaterEpochV2.commitUniform(
            file,received,atlas,Map.of(M,weather(M,2,10,0)),Map.of(),
            Set.of(M),2,86400);
        assertEquals(5,one.water().water().state().dischargedM3().get(M),1e-8);
        assertEquals(one.checkpoint(),HydrologyCheckpointStoreV2.load(file));
        var two = HydrologySeasonalSnowSoilWaterEpochV2.commitUniform(
            file,HydrologyCheckpointStoreV2.load(file),atlas,
            Map.of(B,weather(B,0,10,0),M,weather(M,0,10,0)),
            Map.of(),Set.of(M),2,86400);
        assertEquals(5,two.water().water().state().dischargedM3().get(M),1e-8);
        assertTrue(two.water().water().claimedM3().isEmpty());
        assertThrows(IOException.class,()->HydrologySeasonalSnowSoilWaterEpochV2.commitUniform(
            file,received,atlas,Map.of(M,weather(M,2,10,0)),Map.of(),
            Set.of(M),2,86400));
        assertEquals(two.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

    @Test void refusesIncorrectSphericalAreaAndMissingPersistedCell() {
        var atlas = atlas();
        var first = initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var incorrect = new HydrologySnowSoilStateV2.Forcing(
            1,10,0,0,1,0,1);
        assertThrows(IllegalArgumentException.class,()->advance(
            first,atlas,Map.of(M,incorrect),Map.of()));
        assertThrows(IllegalArgumentException.class,()->advance(
            first,atlas,Map.of(B,weather(B,1,10,0)),Map.of()));
    }

    @Test void rejectsChangedAtlasAndMalformedCombinedPayload() {
        var atlas = atlas();
        var first = initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var changedSources = new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        changedSources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for (long y=1;y<4;y++) for (long x=0;x<8;x++)
            changedSources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        changedSources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        var extraMouth = at(0,2);
        var wrong = HydrologySphericalRouteAtlasV2.compile(
            changedSources,a->a.equals(M)||a.equals(extraMouth)?0:10,
            Set.of(M,extraMouth));
        assertThrows(IllegalArgumentException.class,()->advance(
            first,wrong,Map.of(M,weather(M,1,10,0)),Map.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalSnowSoilWaterEpochV2.decode(first.worldState()+"\n"));
    }

    @Test void refusesBindingExistingReceiptsOrMismatchedWorldIdentity() {
        var atlas = atlas();
        var first = initial(atlas,Map.of(),Map.of());
        var bound = HydrologySeasonalSnowSoilWaterEpochV2.decode(first.worldState());
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySeasonalSnowSoilWaterEpochV2.State(
                RADIUS,bound.topologyState(),
                HydrologySnowSoilStateV2.encode(
                    new HydrologySnowSoilStateV2.State("other",Map.of()))));
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,at(4,2),M),1);
        var withReceipt = new HydrologyCheckpointV2.Checkpoint(0,
            bound.topologyState(),HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalSnowSoilWaterEpochV2.bindFresh(
                withReceipt,new HydrologySnowSoilStateV2.State("world",Map.of()),RADIUS));
    }
    private static HydrologySeasonalSnowSoilWaterEpochV2.Prepared streaming(
            HydrologyCheckpointV2.Checkpoint old,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Set<GlobalHydrologyAddressV2.Sample> loaded) {
        return HydrologySeasonalSnowSoilWaterEpochV2.prepareStreamingUniform(
            old,atlas,forcing,Map.of(),loaded,2,86400);
    }

    @Test void unloadedSnowIsWeatherForcedAndMeltWaitsForRegionLoading() {
        var atlas=atlas();
        var first=initial(atlas,Map.of(),Map.of(
            M,new HydrologySnowSoilStateV2.Cell(0,0),
            B,new HydrologySnowSoilStateV2.Cell(0,0)));
        var cold=streaming(first,atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,2,-5,0)),Set.of(B));
        assertEquals(2,cold.snow().storedVolume(),1e-8);
        assertEquals(0,cold.snow().runoffVolume(),1e-8);
        double meltDepth=2/HydrologySphericalCellAreaV2.areaAt(M,RADIUS)/5;
        var melted=streaming(cold.checkpoint(),atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,0,5,meltDepth)),Set.of(B));
        assertEquals(2,melted.snow().runoffVolume(),1e-8);
        assertEquals(2,melted.water().water().state().lakes().pendingM3().get(M),1e-8);
        assertTrue(melted.water().water().state().dischargedM3().isEmpty());
        var arrived=streaming(melted.checkpoint(),atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,0,10,0)),Set.of(B,M));
        assertEquals(2,arrived.water().water().state().dischargedM3().get(M),1e-8);
        assertEquals(0,arrived.water().water().state().lakes().pendingM3()
            .getOrDefault(M,0.0),1e-8);
    }

    @Test void unloadedRunoffAccumulatesWithoutReintroducingOldWaterAsRain() {
        var atlas=atlas();
        var first=initial(atlas,Map.of(),Map.of(
            M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var one=streaming(first,atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,2,10,0)),Set.of(B));
        assertEquals(2,one.water().water().state().lakes().pendingM3().get(M),1e-8);
        var two=streaming(one.checkpoint(),atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,3,10,0)),Set.of(B));
        assertEquals(5,two.water().water().state().lakes().pendingM3().get(M),1e-8);
        var three=streaming(two.checkpoint(),atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,0,10,0)),Set.of(B,M));
        assertEquals(5,three.water().water().state().dischargedM3().get(M),1e-8);
        var four=streaming(three.checkpoint(),atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,0,10,0)),Set.of(M));
        assertEquals(5,four.water().water().state().dischargedM3().get(M),1e-8);
    }

    @Test void unloadedRunoffAndDurableReceiptConvergeExactlyOnce() {
        var atlas=atlas();
        var first=initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,at(4,2),M),3);
        var received=new HydrologyCheckpointV2.Checkpoint(
            0,first.worldState(),HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var unloaded=streaming(received,atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,2,10,0)),Set.of(B));
        assertEquals(2,unloaded.water().water().state().lakes().pendingM3().get(M),1e-8);
        assertTrue(unloaded.water().water().claimedM3().isEmpty());
        var loaded=streaming(unloaded.checkpoint(),atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,0,10,0)),Set.of(M));
        assertEquals(5,loaded.water().water().state().dischargedM3().get(M),1e-8);
        assertEquals(3,loaded.water().water().claimedM3().get(M),1e-8);
        var again=streaming(loaded.checkpoint(),atlas,Map.of(
            B,weather(B,0,10,0),M,weather(M,0,10,0)),Set.of(M));
        assertTrue(again.water().water().claimedM3().isEmpty());
        assertEquals(5,again.water().water().state().dischargedM3().get(M),1e-8);
    }

    @Test void streamingRequiresEveryPersistedDormantCellToReceiveWeather() {
        var atlas=atlas();
        var first=initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        assertThrows(IllegalArgumentException.class,()->streaming(first,atlas,
            Map.of(B,weather(B,0,10,0)),Set.of(B)));
        assertThrows(IllegalArgumentException.class,()->streaming(first,atlas,
            Map.of(B,weather(B,0,10,0),M,weather(M,0,10,0),at(2,2),
                weather(at(2,2),0,10,0)),Set.of(B)));
        assertThrows(IllegalArgumentException.class,()->advance(first,atlas,
            Map.of(B,weather(B,0,10,0)),Map.of()));
    }

    @Test void streamingScheduledAndUniformMatchForNoLakeAndUnloadedRunoff() {
        var atlas=atlas();
        var first=initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var forcing=Map.of(B,weather(B,0,10,0),M,weather(M,2,10,0));
        var uniform=streaming(first,atlas,forcing,Set.of(B));
        var scheduled=HydrologySeasonalSnowSoilWaterEpochV2.prepareStreamingScheduled(
            first,atlas,forcing,Map.of(),Map.of(),Set.of(B),86400);
        assertEquals(uniform.checkpoint(),scheduled.checkpoint());
    }

    @Test void streamingCheckpointSurvivesRestartAndRejectsStaleWriter() throws IOException {
        var atlas=atlas();
        var first=initial(atlas,Map.of(),Map.of(M,new HydrologySnowSoilStateV2.Cell(0,0)));
        var file=directory.resolve("streaming-water.chk");
        HydrologyCheckpointStoreV2.save(file,first);
        var pending=HydrologySeasonalSnowSoilWaterEpochV2.commitStreamingUniform(
            file,first,atlas,Map.of(B,weather(B,0,10,0),M,weather(M,2,10,0)),
            Map.of(),Set.of(B),2,86400);
        assertEquals(pending.checkpoint(),HydrologyCheckpointStoreV2.load(file));
        var arrived=HydrologySeasonalSnowSoilWaterEpochV2.commitStreamingUniform(
            file,HydrologyCheckpointStoreV2.load(file),atlas,
            Map.of(M,weather(M,0,10,0)),Map.of(),Set.of(M),2,86400);
        assertEquals(2,arrived.water().water().state().dischargedM3().get(M),1e-8);
        assertThrows(IOException.class,()->HydrologySeasonalSnowSoilWaterEpochV2.commitStreamingUniform(
            file,first,atlas,Map.of(B,weather(B,0,10,0),M,weather(M,2,10,0)),
            Map.of(),Set.of(B),2,86400));
        assertEquals(arrived.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

}
