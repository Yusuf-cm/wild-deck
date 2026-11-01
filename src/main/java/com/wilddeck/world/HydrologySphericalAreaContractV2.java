package com.wilddeck.world;

import java.io.IOException;
import java.util.*;

/**
 * Opt-in spherical area contract for quiescent seasonal lake transitions.
 *
 * A depth is not a conserved volume without its horizontal cell area.
 * Verify all stored lake columns and all seasonal weather cells against one
 * explicit planetary radius before changing a durable water checkpoint.
 * This does not persist radius provenance, support mixed LOD, or make the
 * underlying local-file checkpoint an interprocess transaction.
 */
public final class HydrologySphericalAreaContractV2 {
    private HydrologySphericalAreaContractV2() {}

    public static void verifyPlan(HydrologySphericalLakeBasinsV2.Plan plan,
                                  double radiusMeters) {
        Objects.requireNonNull(plan);
        HydrologySphericalCellAreaV2.planetArea(radiusMeters);
        var routes=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:plan.routes()) {
            Objects.requireNonNull(node);
            if(!HydrologySphericalTopologyV2.isCanonical(node.address()) ||
               !routes.add(node.address()))
                throw new IllegalArgumentException("Invalid spherical route ownership");
        }
        if(routes.isEmpty())
            throw new IllegalArgumentException("Empty spherical plan");
        var wet=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var lake:plan.lakes()) {
            Objects.requireNonNull(lake);
            var areas=lake.reservoir().cellArea();
            if(areas.length!=lake.cells().size())
                throw new IllegalArgumentException("Lake area length mismatch");
            for(int i=0;i<areas.length;i++) {
                var address=Objects.requireNonNull(lake.cells().get(i));
                if(!routes.contains(address)||!wet.add(address))
                    throw new IllegalArgumentException("Unknown or multiply owned wet cell");
                requireArea(areas[i],address,radiusMeters);
            }
        }
    }

    public static void verifyWeather(
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters) {
        Objects.requireNonNull(weather);
        HydrologySphericalCellAreaV2.planetArea(radiusMeters);
        for(var entry:weather.entrySet()) {
            var address=Objects.requireNonNull(entry.getKey());
            var forcing=Objects.requireNonNull(entry.getValue());
            requireArea(forcing.cellArea(),address,radiusMeters);
        }
    }

    /**
     * Advance a geometry-bound seasonal checkpoint with independently
     * checked spherical area ownership. Rejects incompatible forcing before
     * receipts are claimed or a new epoch is persisted.
     */
    public static HydrologySeasonalLakeNetworkV2.Advanced advanceBound(
            HydrologyWorldRunnerV2 runner,String nextWorld,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> lakeForcing,
            double radiusMeters) throws IOException {
        verifyPlan(plan,radiusMeters);
        verifyWeather(weather,radiusMeters);
        return HydrologySeasonalLakeNetworkV2.advanceBound(
            runner,nextWorld,atlas,plan,weather,lakeForcing);
    }

    /**
     * Verify old and rebuilt lake column areas against the SAME radius before
     * the quiescent seasonal reservoir remap. All existing guards on route
     * identity, pending receipts and stale checkpoints still apply.
     */
    public static HydrologyWetLakeDrainageActivationV2.Committed
            activateSeasonalDisplaced(
                HydrologyWorldRunnerV2 runner,
                HydrologySphericalRouteAtlasV2 previous,
                HydrologySphericalAtlasRebuildAuditV2.Result audit,
                HydrologySphericalLakeBasinsV2.Plan oldLakes,
                HydrologySphericalLakeBasinsV2.Plan newLakes,
                double radiusMeters) throws IOException {
        verifyPlan(oldLakes,radiusMeters);
        verifyPlan(newLakes,radiusMeters);
        return runner.activateSeasonalDisplacedWetLakeDrainage(
            previous,audit,oldLakes,newLakes);
    }

    private static void requireArea(double actual,
            GlobalHydrologyAddressV2.Sample address,double radiusMeters) {
        double expected=HydrologySphericalCellAreaV2.areaAt(address,radiusMeters);
        // No arbitrary tolerance: both sides use the same deterministic
        // spherical cell-area formula, and area drift changes stored volume.
        if(Double.doubleToLongBits(actual)!=Double.doubleToLongBits(expected))
            throw new IllegalArgumentException(
                "Cell area disagrees with planetary radius at "+address);
    }
}
