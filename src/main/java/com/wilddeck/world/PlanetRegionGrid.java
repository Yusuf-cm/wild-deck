package com.wilddeck.world;

/**
 * Nested equirectangular addressing for streaming and cache keys.
 * Grid boundaries are inclusive sample coordinates; adjacent regions share
 * the same exact sample point. This is NOT the geometry of the planet.
 */
public final class PlanetRegionGrid {
    private PlanetRegionGrid() {}
    public record Region(int level, long column, long row) {
        public Region {
            if (level < 0 || level > 30) throw new IllegalArgumentException("Invalid level");
            long width = 2L << level, height = 1L << level;
            if (column < 0 || column >= width || row < 0 || row >= height)
                throw new IllegalArgumentException("Region outside planet");
        }
    }
    public static Planet.SurfacePoint point(Region region, int x, int y, int divisions) {
        if (region == null) throw new NullPointerException("region");
        if (divisions < 1 || divisions > 4096 || x < 0 || y < 0 || x > divisions || y > divisions)
            throw new IllegalArgumentException("Invalid region sample");
        long width = 2L << region.level(), height = 1L << region.level();
        double lon = -180.0 + 360.0 * ((region.column() + (double)x / divisions) / width);
        double lat = 90.0 - 180.0 * ((region.row() + (double)y / divisions) / height);
        return new Planet.SurfacePoint(lat, lon);
    }
    public static Region parent(Region child) {
        if (child.level() == 0) throw new IllegalArgumentException("Root has no parent");
        return new Region(child.level()-1, child.column()/2, child.row()/2);
    }
}
