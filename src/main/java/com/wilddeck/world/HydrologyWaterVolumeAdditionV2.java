package com.wilddeck.world;

/**
 * Conservation guard for adding two independently owned SI water volumes.
 * Floating-point rounding is tolerated unless it erases an ENTIRE positive
 * contribution. This is not arbitrary-precision hydrology or a replacement
 * for the seasonal mass-balance audit.
 */
public final class HydrologyWaterVolumeAdditionV2 {
    private HydrologyWaterVolumeAdditionV2() {}

    public static double add(double storedM3, double incomingM3) {
        if (!Double.isFinite(storedM3) || storedM3 < 0 ||
            !Double.isFinite(incomingM3) || incomingM3 < 0)
            throw new IllegalArgumentException("Invalid water volume");
        double total = storedM3 + incomingM3;
        if (!Double.isFinite(total) ||
            (storedM3 > 0 && incomingM3 > 0 &&
             (total == storedM3 || total == incomingM3)))
            throw new IllegalArgumentException(
                "Water addition would erase a positive volume");
        return total == 0 ? 0.0 : total;
    }
}
