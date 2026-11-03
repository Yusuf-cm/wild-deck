package com.wilddeck.world;

/**
 * Fail-closed addition of two independently accounted sediment masses in kg.
 * Detects overflow and complete loss of either positive contribution in a
 * double-precision confluence. Does not guarantee exact decimal conservation.
 */
public final class SedimentMassAdditionV2 {
    private SedimentMassAdditionV2() {}

    public static double add(double existingKg, double incomingKg) {
        if (!Double.isFinite(existingKg) || existingKg < 0 ||
            !Double.isFinite(incomingKg) || incomingKg < 0)
            throw new IllegalArgumentException("Invalid sediment mass");
        double total = existingKg + incomingKg;
        if (!Double.isFinite(total) ||
            (existingKg > 0 && incomingKg > 0 &&
             (total == existingKg || total == incomingKg)))
            throw new IllegalArgumentException("Sediment addition would erase positive mass");
        return total == 0 ? 0.0 : total;
    }
}
