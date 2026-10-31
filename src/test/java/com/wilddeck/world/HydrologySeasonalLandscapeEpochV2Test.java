package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLandscapeEpochV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000;

    private static List<GlobalHydrologyAddressV2.Sample> window() {
        var result = new ArrayList<GlobalHydrologyAddressV2.Sample>();
        for (int y = 4; y < 9; y++)
            for (int x = 4; x < 9; x++)
                result.add(new GlobalHydrologyAddressV2.Sample(2, 4, x, y));
        return List.copyOf(result);
    }

    private static String initial() {
        var state = new HydrologySeasonalLandscapeStateV2.State("world", RADIUS,
            new HydrologyTerrainOverlayV2.State("world", Map.of()),
            new HydrologySnowSoilStateV2.State("world", Map.of()));
        return HydrologySeasonalLandscapeStateV2.encode(state);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing>
            weather(double precipitation) {
        var result = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                       HydrologySnowSoilStateV2.Forcing>();
        for (var address : window())
            result.put(address, HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address, RADIUS, precipitation, 5, 0, 0, 1, 10));
        return result;
    }

    private static HydrologySeasonalLandscapeEpochV2.Committed advance(
            HydrologyWorldRunnerV2 runner, double precipitation,
            HydrologyGlobalSlopeRouterV2.Elevations elevations) throws IOException {
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        return HydrologySeasonalLandscapeEpochV2.advanceClosedWindow(
            runner, window(), 5, 5, elevations, weather(precipitation),
            RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24));
    }

    private static HydrologyGlobalSlopeRouterV2.Elevations slope() {
        return a -> 20 - (a.x() - 4) - (a.y() - 4);
    }

    @Test void commitsRunoffTerrainAndSnowSoilTogetherAcrossRestarts()
            throws IOException {
        Path path = directory.resolve("seasonal.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var first = advance(runner, 2, slope());
        assertEquals(1, runner.epoch());
        assertTrue(first.budget().input() > 0);
        assertEquals(first.budget().input(), first.budget().outlet(), 1e-6);
        assertEquals(0, first.budget().frontier(), 1e-12);
        assertTrue(first.prepared().preview().erosion().terrain()
            .landscape().erodedMass() > 0);
        var saved = HydrologySeasonalLandscapeStateV2.decode(runner.worldState());
        assertEquals(first.prepared().state(), saved);
        assertEquals(25, saved.terrain().elevations().size());
        assertEquals(25, saved.snowSoil().cells().size());

        runner = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        var second = advance(runner, 1, slope());
        assertEquals(2, runner.epoch());
        assertEquals(second.prepared().state(),
            HydrologySeasonalLandscapeStateV2.decode(
                HydrologyWorldRunnerV2.open(path, 0, "ignored").worldState()));
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
    }

    @Test void rejectsPondedDepressionWithoutCommittingInstantaneousSpill()
            throws IOException {
        Path path = directory.resolve("pond.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var before = HydrologyCheckpointStoreV2.load(path);
        assertThrows(IllegalStateException.class, () -> advance(runner, 2,
            a -> a.x() == 6 && a.y() == 6 ? -100 : 20 - (a.x() - 4) - (a.y() - 4)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(path));
        assertEquals(0, runner.epoch());
    }

    @Test void pendingBoundaryWaterAndInvalidRadiusFailBeforeCommit()
            throws IOException {
        Path path = directory.resolve("pending.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var key = new HydrologyTransferLedgerV2.Key(3,
            new GlobalHydrologyAddressV2.Sample(2, 4, 3, 4),
            new GlobalHydrologyAddressV2.Sample(2, 4, 4, 4));
        runner.receive(key, 5);
        var before = HydrologyCheckpointStoreV2.load(path);
        assertThrows(IllegalStateException.class, () -> advance(runner, 2, slope()));
        assertEquals(before, HydrologyCheckpointStoreV2.load(path));
        assertEquals(5, runner.receiptBudget().pendingVolume(), 1e-12);

        var dry = HydrologyWorldRunnerV2.open(directory.resolve("radius.chk"), 0, initial());
        var dryBefore = dry.worldState();
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceClosedWindow(
                dry, window(), 5, 5, slope(), weather(2),
                RADIUS + 1, 2000, 0.01, 0.05, capacity, Set.of(24)));
        assertEquals(0, dry.epoch());
        assertEquals(dryBefore, dry.worldState());
    }

    @Test void staleRunnerCannotOverwriteNewlyDeliveredWater()
            throws IOException {
        Path path = directory.resolve("stale.chk");
        var stale = HydrologyWorldRunnerV2.open(path, 0, initial());
        var writer = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        var key = new HydrologyTransferLedgerV2.Key(3,
            new GlobalHydrologyAddressV2.Sample(2, 4, 3, 4),
            new GlobalHydrologyAddressV2.Sample(2, 4, 4, 4));
        writer.receive(key, 7);
        assertThrows(IOException.class, () -> advance(stale, 2, slope()));
        var recovered = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(0, recovered.epoch());
        assertEquals(7, recovered.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(initial(), recovered.worldState());
    }
}
