package com.wilddeck.world;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in single-payload composition of seasonal snow/soil stores and an
 * erosion/sediment terrain overlay. Both states are encoded together for use
 * as ONE opaque world checkpoint payload, so a restart cannot recover a new
 * snowpack with an old bed elevation (or vice versa).
 *
 * Preparing a transition does NOT commit runoff, receipts, or changed river
 * topology. Callers must independently audit global routing and activate the
 * resulting water/terrain change in one authoritative epoch. This class does
 * not provide a distributed transaction or a complete hydrologic world save.
 */
public final class HydrologySeasonalLandscapeStateV2 {
    private HydrologySeasonalLandscapeStateV2() {}
    private static final String MAGIC = "WD-SEASONAL-LANDSCAPE-2";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    public record State(String world, double radiusMeters,
                        HydrologyTerrainOverlayV2.State terrain,
                        HydrologySnowSoilStateV2.State snowSoil) {
        public State {
            Objects.requireNonNull(world);
            HydrologySphericalCellAreaV2.planetArea(radiusMeters);
            Objects.requireNonNull(terrain);
            Objects.requireNonNull(snowSoil);
            if (!world.equals(terrain.world()) || !world.equals(snowSoil.world()))
                throw new IllegalArgumentException("Landscape stores have different worlds");
        }
    }

    public record Prepared(State state,
            HydrologySeasonalErosionPreviewV2.ForcedPreview preview) {
        public Prepared {
            Objects.requireNonNull(state);
            Objects.requireNonNull(preview);
            if (!state.terrain().equals(preview.erosion().terrain().state()) ||
                !state.snowSoil().equals(preview.season().state()))
                throw new IllegalArgumentException("Prepared stores differ from audited preview");
        }
    }

    /**
     * Recompute runoff and sediment from the same previous state and forcing.
     * Returns a noncommitting candidate containing BOTH new stores.
     */
    public static Prepared prepare(
            State previous, List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets) {
        Objects.requireNonNull(previous);
        if (Double.compare(previous.radiusMeters(), radiusMeters) != 0)
            throw new IllegalArgumentException("Seasonal landscape planetary radius changed");
        var preview = HydrologySeasonalErosionPreviewV2.calculateFromForcing(
            previous.terrain(), addresses, width, height, seed,
            previous.snowSoil(), weather, radiusMeters, sedimentDensity,
            erosionStrength, maximumCut, transportCapacity, verifiedOutlets);
        return new Prepared(new State(previous.world(), previous.radiusMeters(),
            preview.erosion().terrain().state(), preview.season().state()), preview);
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        byte[] terrain = HydrologyTerrainOverlayV2.encode(state.terrain())
            .getBytes(StandardCharsets.UTF_8);
        byte[] snowSoil = HydrologySnowSoilStateV2.encode(state.snowSoil())
            .getBytes(StandardCharsets.UTF_8);
        if ((long)terrain.length + snowSoil.length > MAX_BYTES)
            throw new IllegalArgumentException("Seasonal landscape payload too large");
        return MAGIC + "\n" + Double.toHexString(state.radiusMeters()) + "\n" +
            Base64.getEncoder().encodeToString(terrain) + "\n" +
            Base64.getEncoder().encodeToString(snowSoil) + "\n";
    }

    public static State decode(String payload) {
        Objects.requireNonNull(payload);
        var lines = payload.split("\\n", -1);
        if (lines.length != 5 || !MAGIC.equals(lines[0]) || !lines[4].isEmpty())
            throw new IllegalArgumentException("Unsupported seasonal landscape payload");
        try {
            double radiusMeters = Double.parseDouble(lines[1]);
            HydrologySphericalCellAreaV2.planetArea(radiusMeters);
            byte[] terrain = Base64.getDecoder().decode(lines[2]);
            byte[] snowSoil = Base64.getDecoder().decode(lines[3]);
            if ((long)terrain.length + snowSoil.length > MAX_BYTES)
                throw new IllegalArgumentException("Seasonal landscape payload too large");
            var decoder = StandardCharsets.UTF_8.newDecoder();
            var terrainState = HydrologyTerrainOverlayV2.decode(
                decoder.decode(ByteBuffer.wrap(terrain)).toString());
            decoder.reset();
            var snowSoilState = HydrologySnowSoilStateV2.decode(
                decoder.decode(ByteBuffer.wrap(snowSoil)).toString());
            var state = new State(terrainState.world(), radiusMeters,
                terrainState, snowSoilState);
            if (!encode(state).equals(payload))
                throw new IllegalArgumentException("Noncanonical seasonal landscape payload");
            return state;
        } catch (CharacterCodingException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed seasonal landscape payload", ex);
        }
    }
}
