package com.wilddeck.world;

import java.util.Objects;

/**
 * Versioned authoritative terrain sampling contract.
 * A region is an address, never a separate random world.
 * v1 remains untouched for backwards-compatible saved seeds.
 */
public final class PlanetSurfaceV2 {
    public static final String VERSION = "planet-surface-v2";
    public record TerrainSample(double elevationMeters, double latitudeDeg, double longitudeDeg) {
        public TerrainSample {
            if (!Double.isFinite(elevationMeters)) throw new IllegalArgumentException("Invalid elevation");
        }
    }
    private final Planet planet;
    private final long seed;
    private final PlanetSurfaceGenerator legacy;

    public PlanetSurfaceV2(Planet planet, long seed) {
        this.planet = Objects.requireNonNull(planet);
        this.seed = seed;
        this.legacy = new PlanetSurfaceGenerator(planet, seed);
    }
    public long seed() { return seed; }
    public Planet planet() { return planet; }
    public long layerSeed(long channel) { return WorldSeedChannels.derive(seed, channel); }

    /**
     * First v2 increment deliberately delegates macro relief to v1.
     * Sub-meter terrain, hydrology, and geological provinces are NOT yet implemented.
     */
    public TerrainSample sample(Planet.SurfacePoint point) {
        Objects.requireNonNull(point);
        var base = legacy.sample(point);
        return new TerrainSample(base.elevationMeters(), point.latitudeDeg(), point.longitudeDeg());
    }

    public TerrainSample sample(PlanetRegionGrid.Region region, int x, int y, int divisions) {
        return sample(PlanetRegionGrid.point(region, x, y, divisions));
    }
}
