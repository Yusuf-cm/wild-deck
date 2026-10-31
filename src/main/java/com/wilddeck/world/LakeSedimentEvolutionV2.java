package com.wilddeck.world;

import java.util.*;

/**
 * A local, finite-volume lake-bed update for a known, fixed spill elevation.
 * Deposited sediment fills the lowest lake columns first, levelling the bed
 * across equal-elevation cells. Water displaced by lost storage is overflow,
 * never silently destroyed. Sediment beyond the basin's capacity is exported.
 *
 * No global lake identity, sediment transport, spillway discovery or terrain
 * persistence is implied: callers must route both exported fluxes and persist
 * the returned geometry. All quantities use consistent physical units.
 */
public final class LakeSedimentEvolutionV2 {
    private LakeSedimentEvolutionV2() {}

    public record Result(Optional<LakeReservoirV2.Basin> basin,
                         double[] bedElevation, double storedWater,
                         double displacedWater, double depositedSediment,
                         double exportedSediment) {
        public Result {
            Objects.requireNonNull(basin);
            Objects.requireNonNull(bedElevation);
            bedElevation = bedElevation.clone();
            if (!Double.isFinite(storedWater) || !Double.isFinite(displacedWater) ||
                !Double.isFinite(depositedSediment) || !Double.isFinite(exportedSediment) ||
                storedWater < 0 || displacedWater < 0 ||
                depositedSediment < 0 || exportedSediment < 0)
                throw new IllegalArgumentException("Invalid lake-sediment result");
        }
        @Override public double[] bedElevation() { return bedElevation.clone(); }
    }

    /**
     * Sediment mass / bulk dry density gives deposited bulk volume. The basin
     * may completely silt up; in that case its Optional becomes empty and all
     * remaining water is discharged. Bed cells above the spill are untouched.
     */
    public static Result deposit(LakeReservoirV2.Basin basin, double storedWater,
                                 double sedimentMass, double sedimentDensity) {
        Objects.requireNonNull(basin);
        double capacity = basin.capacity();
        if (!Double.isFinite(storedWater) || storedWater < 0 || storedWater > capacity ||
            !Double.isFinite(sedimentMass) || sedimentMass < 0 ||
            !Double.isFinite(sedimentDensity) || sedimentDensity <= 0)
            throw new IllegalArgumentException("Invalid sediment or stored water");
        double maximumMass = capacity * sedimentDensity;
        if (!Double.isFinite(maximumMass) || maximumMass == 0)
            throw new IllegalArgumentException("Unrepresentable sediment capacity");

        double[] beds = basin.bedElevation();
        double[] areas = basin.cellArea();
        double spill = basin.spillElevation();
        double depositedTarget = Math.min(sedimentMass, maximumMass);
        double volume = depositedTarget / sedimentDensity;
        if (!Double.isFinite(volume))
            throw new IllegalArgumentException("Sediment volume overflow");

        if (depositedTarget == maximumMass) {
            for (int i = 0; i < beds.length; i++)
                if (beds[i] < spill) beds[i] = spill;
        } else if (volume > 0) {
            Integer[] sorted = new Integer[beds.length];
            for (int i = 0; i < sorted.length; i++) sorted[i] = i;
            Arrays.sort(sorted, Comparator.comparingDouble((Integer i) -> beds[i])
                                          .thenComparingInt(i -> i));
            double level = beds[sorted[0]];
            double activeArea = 0;
            int index = 0;
            while (index < sorted.length && beds[sorted[index]] <= level)
                activeArea += areas[sorted[index++]];
            if (!Double.isFinite(activeArea))
                throw new IllegalArgumentException("Lake area overflow");
            double remaining = volume;
            while (remaining > 0 && level < spill) {
                double next = index < sorted.length ?
                    Math.min(spill, beds[sorted[index]]) : spill;
                double needed = (next - level) * activeArea;
                if (!Double.isFinite(needed))
                    throw new IllegalArgumentException("Lake sediment overflow");
                if (remaining < needed) {
                    level += remaining / activeArea;
                    remaining = 0;
                } else {
                    level = next;
                    remaining -= needed;
                    while (index < sorted.length && beds[sorted[index]] <= level) {
                        activeArea += areas[sorted[index++]];
                        if (!Double.isFinite(activeArea))
                            throw new IllegalArgumentException("Lake area overflow");
                    }
                }
            }
            for (int i = 0; i < beds.length; i++)
                if (beds[i] < spill) beds[i] = Math.min(spill, Math.max(beds[i], level));
        }

        boolean stillLake = false;
        for (double bed : beds) if (bed < spill) stillLake = true;
        Optional<LakeReservoirV2.Basin> updated = stillLake ?
            Optional.of(new LakeReservoirV2.Basin(beds, areas, spill)) : Optional.empty();
        double newCapacity = updated.map(LakeReservoirV2.Basin::capacity).orElse(0.0);
        double retained = Math.min(storedWater, newCapacity);
        double displaced = storedWater - retained;
        // Accounting is based on the geometry, not an assumed sediment target.
        double actualVolume = capacity - newCapacity;
        double deposited = Math.min(sedimentMass, actualVolume * sedimentDensity);
        if (!Double.isFinite(deposited))
            throw new IllegalArgumentException("Sediment accounting overflow");
        double exported = sedimentMass - deposited;
        return new Result(updated, beds, retained, displaced, deposited, exported);
    }
}
