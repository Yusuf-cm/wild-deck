package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLandscapeStateV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000;

    private static HydrologySeasonalLandscapeStateV2.State initial() {
        return new HydrologySeasonalLandscapeStateV2.State("world",
            new HydrologyTerrainOverlayV2.State("world", Map.of()),
            new HydrologySnowSoilStateV2.State("world", Map.of()));
    }

    private static List<GlobalHydrologyAddressV2.Sample> window() {
        var result = new ArrayList<GlobalHydrologyAddressV2.Sample>();
        for (int y = 4; y < 9; y++) for (int x = 4; x < 9; x++)
            result.add(new GlobalHydrologyAddressV2.Sample(2, 4, x, y));
        return List.copyOf(result);
    }

    private static HydrologySeasonalLandscapeStateV2.Prepared prepare(
            HydrologySeasonalLandscapeStateV2.State previous) {
        var weather = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                        HydrologySnowSoilStateV2.Forcing>();
        for (var address : window())
            weather.put(address, HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address, RADIUS, 2, 5, 0, 0, 1, 10));
        double[] capacity = new double[25];
        Arrays.fill(capacity, 2_000_000);
        return HydrologySeasonalLandscapeStateV2.prepare(
            previous, window(), 5, 5,
            a -> 20 - (a.x() - 4) - (a.y() - 4), weather,
            RADIUS, 2000, 0.1, 0.4, capacity, Set.of(24));
    }

    @Test void seasonalStoresReplayTogetherWithoutChangingPreviousState() {
        var before = initial();
        var first = prepare(before);
        var replay = prepare(before);
        assertEquals(first.state(), replay.state());
        assertEquals(first.state().terrain(), first.preview().erosion().terrain().state());
        assertEquals(first.state().snowSoil(), first.preview().season().state());
        assertEquals(first.preview().erosion().runoffVolume(),
            first.preview().erosion().terminalVolume(), 1e-6);
        assertTrue(first.preview().erosion().terrain().landscape().erodedMass() > 0);
        assertTrue(before.terrain().elevations().isEmpty());
        assertTrue(before.snowSoil().cells().isEmpty());
        assertEquals(25, first.state().terrain().elevations().size());
        assertEquals(25, first.state().snowSoil().cells().size());
        assertEquals(first.state(), HydrologySeasonalLandscapeStateV2.decode(
            HydrologySeasonalLandscapeStateV2.encode(first.state())));
    }

    @Test void bothStoresSurviveOneWorldCheckpointRestart() throws IOException {
        var prepared = prepare(initial());
        var path = directory.resolve("seasonal-landscape.chk");
        HydrologyWorldRunnerV2.open(path, 0,
            HydrologySeasonalLandscapeStateV2.encode(prepared.state()));
        var reopened = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(0, reopened.epoch());
        assertEquals(prepared.state(), HydrologySeasonalLandscapeStateV2.decode(
            reopened.worldState()));
        assertTrue(reopened.inboxSnapshot().receipts().isEmpty());
    }

    @Test void rejectsMismatchedWorldsAndTamperedPayloads() {
        var before = initial();
        assertThrows(IllegalArgumentException.class, () ->
            new HydrologySeasonalLandscapeStateV2.State("world",
                new HydrologyTerrainOverlayV2.State("other", Map.of()),
                before.snowSoil()));
        assertThrows(IllegalArgumentException.class, () ->
            new HydrologySeasonalLandscapeStateV2.State("world",
                before.terrain(),
                new HydrologySnowSoilStateV2.State("other", Map.of())));
        var encoded = HydrologySeasonalLandscapeStateV2.encode(before);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeStateV2.decode(encoded + "junk"));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeStateV2.decode(
                encoded.replace("WD-SEASONAL-LANDSCAPE-1",
                                "WD-SEASONAL-LANDSCAPE-2")));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeStateV2.decode(
                encoded.replaceFirst("\\n", "\\n!")));
    }

    @Test void rejectsForgedPreparedStoresAndIncorrectAreaProvenance() {
        var prepared = prepare(initial());
        assertThrows(IllegalArgumentException.class, () ->
            new HydrologySeasonalLandscapeStateV2.Prepared(initial(),
                prepared.preview()));
        var weather = new HashMap<GlobalHydrologyAddressV2.Sample,
                                  HydrologySnowSoilStateV2.Forcing>();
        for (var address : window())
            weather.put(address, HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address, RADIUS + 1, 2, 5, 0, 0, 1, 10));
        double[] capacity = new double[25];
        Arrays.fill(capacity, 2_000_000);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeStateV2.prepare(
                initial(), window(), 5, 5, a -> 20, weather,
                RADIUS, 2000, 0.1, 0.4, capacity, Set.of(24)));
    }
}
