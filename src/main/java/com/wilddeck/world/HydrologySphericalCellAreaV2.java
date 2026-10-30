package com.wilddeck.world;

import java.util.Objects;

/**
 * Physical horizontal surface area owned by one canonical spherical rainfall
 * sample. Longitude cells are centered on the sample; each pole owns its
 * entire half-latitude cap, so seam and polar vertices are counted only once.
 *
 * This is an equirectangular sampling-area convention, not a terrain-slope
 * area or a mixed-resolution remapping. Areas are in square metres.
 */
public final class HydrologySphericalCellAreaV2 {
    private HydrologySphericalCellAreaV2() {}

    public static double areaAt(GlobalHydrologyAddressV2.Sample address,
                                double radiusMeters) {
        Objects.requireNonNull(address);
        if (!HydrologySphericalTopologyV2.isCanonical(address))
            throw new IllegalArgumentException("Noncanonical spherical cell");
        checkedRadius(radiusMeters);
        long longitudeCells=(2L<<address.level())*address.divisions();
        long latitudeSteps=(1L<<address.level())*address.divisions();
        double deltaLatitude=StrictMath.PI/latitudeSteps;
        double radiusSquared=radiusMeters*radiusMeters;
        double area;
        if (address.y()==0 || address.y()==latitudeSteps) {
            // 2*pi*R^2*(1-cos(deltaLatitude/2)), evaluated without
            // subtracting nearly equal numbers near a high-resolution pole.
            double sin=StrictMath.sin(deltaLatitude/4);
            area=4*StrictMath.PI*radiusSquared*sin*sin;
        } else {
            double latitude=StrictMath.PI/2-
                StrictMath.PI*((double)address.y()/latitudeSteps);
            double longitudeWidth=2*StrictMath.PI/longitudeCells;
            area=radiusSquared*longitudeWidth*2*
                StrictMath.cos(latitude)*StrictMath.sin(deltaLatitude/2);
        }
        if (!(area>0) || !Double.isFinite(area))
            throw new IllegalArgumentException("Spherical cell area overflow");
        return area;
    }

    public static double planetArea(double radiusMeters) {
        checkedRadius(radiusMeters);
        double area=4*StrictMath.PI*radiusMeters*radiusMeters;
        if (!Double.isFinite(area))
            throw new IllegalArgumentException("Planet area overflow");
        return area;
    }

    private static void checkedRadius(double radiusMeters) {
        if (!Double.isFinite(radiusMeters) || radiusMeters<=0 ||
            !Double.isFinite(radiusMeters*radiusMeters))
            throw new IllegalArgumentException("Invalid planetary radius");
    }
}
