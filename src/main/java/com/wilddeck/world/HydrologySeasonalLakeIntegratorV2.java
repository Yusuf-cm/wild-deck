package com.wilddeck.world;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;

/**
 * Conservative, evenly forced seasonal lake integration. The supplied season's
 * total inflow and potential evaporation depth are distributed over bounded
 * equal-duration substeps, so evaporation can free storage before later rain
 * arrives. This avoids the single-step assumption that every drop arrives
 * before any seasonal evaporation occurs.
 *
 * Uniform forcing is a documented approximation, not a storm hydrograph or
 * calibrated energy balance. The result is noncommitting; caller owns storage.
 */
public final class HydrologySeasonalLakeIntegratorV2 {
    private HydrologySeasonalLakeIntegratorV2() {}
    public static final int MAX_SUBSTEPS = 4096;

    /** Nonnegative inflow and potential evaporation over one ordered interval. */
    public record Interval(double inflowM3, double evaporationDepthMeters) {
        public Interval {
            if (!Double.isFinite(inflowM3) || inflowM3 < 0 ||
                !Double.isFinite(evaporationDepthMeters) || evaporationDepthMeters < 0)
                throw new IllegalArgumentException("Invalid lake interval forcing");
        }
    }

    /**
     * Integrate a known chronological weather/hydrology schedule. Each interval
     * spills before evaporation, but later intervals can use capacity freed by
     * earlier evaporation. Order is significant; no hidden rerouting occurs.
     */
    public static LakeReservoirV2.Step stepSchedule(LakeReservoirV2.Basin basin,
            double previousVolumeM3, List<Interval> schedule) {
        Objects.requireNonNull(basin);
        Objects.requireNonNull(schedule);
        if (schedule.isEmpty() || schedule.size() > MAX_SUBSTEPS ||
            !Double.isFinite(previousVolumeM3) || previousVolumeM3 < 0 ||
            previousVolumeM3 > basin.capacity())
            throw new IllegalArgumentException("Invalid lake schedule or initial storage");
        double volume = previousVolumeM3;
        double inflow = 0, evaporated = 0, overflow = 0;
        for (var interval : schedule) {
            Objects.requireNonNull(interval);
            inflow += interval.inflowM3();
            if (!Double.isFinite(inflow))
                throw new IllegalArgumentException("Seasonal lake input overflow");
            var advanced = LakeReservoirV2.stepInflowThenEvaporation(
                basin, volume, interval.inflowM3(), interval.evaporationDepthMeters());
            volume = advanced.volume();
            evaporated += advanced.evaporated();
            overflow += advanced.overflow();
            if (!Double.isFinite(evaporated) || !Double.isFinite(overflow))
                throw new IllegalArgumentException("Seasonal lake output overflow");
        }
        double available = previousVolumeM3 + inflow;
        double accounted = volume + evaporated + overflow;
        if (!Double.isFinite(available) || !Double.isFinite(accounted) ||
            Math.abs(available - accounted) >
                Math.max(1e-8, 1e-10 * Math.max(available, accounted)))
            throw new IllegalArgumentException("Seasonal lake water conservation violated");
        return new LakeReservoirV2.Step(volume, basin.stageAt(volume),
            evaporated, overflow);
    }

    public static LakeReservoirV2.Step step(LakeReservoirV2.Basin basin,
            double previousVolumeM3, double seasonalInflowM3,
            double seasonalEvaporationDepthMeters, int substeps) {
        Objects.requireNonNull(basin);
        double capacity = basin.capacity();
        if (substeps < 1 || substeps > MAX_SUBSTEPS ||
            !Double.isFinite(previousVolumeM3) || previousVolumeM3 < 0 ||
            previousVolumeM3 > capacity ||
            !Double.isFinite(seasonalInflowM3) || seasonalInflowM3 < 0 ||
            !Double.isFinite(seasonalEvaporationDepthMeters) ||
            seasonalEvaporationDepthMeters < 0)
            throw new IllegalArgumentException("Invalid seasonal lake integration inputs");
        double totalAvailable = previousVolumeM3 + seasonalInflowM3;
        if (!Double.isFinite(totalAvailable))
            throw new IllegalArgumentException("Seasonal lake input overflow");
        if (substeps == 1)
            return LakeReservoirV2.stepInflowThenEvaporation(basin,
                previousVolumeM3, seasonalInflowM3, seasonalEvaporationDepthMeters);

        var schedule = new ArrayList<Interval>(substeps);
        double delivered = 0, depthDelivered = 0;
        for (int i = 0; i < substeps; i++) {
            double inflow = i == substeps - 1
                ? Math.max(0, seasonalInflowM3 - delivered)
                : seasonalInflowM3 / substeps;
            double depth = i == substeps - 1
                ? Math.max(0, seasonalEvaporationDepthMeters - depthDelivered)
                : seasonalEvaporationDepthMeters / substeps;
            if (i != substeps - 1) {
                delivered += inflow;
                depthDelivered += depth;
            }
            schedule.add(new Interval(inflow, depth));
        }
        return stepSchedule(basin, previousVolumeM3, schedule);
    }
}
