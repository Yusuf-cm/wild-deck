package com.wilddeck.world;

import java.util.*;

/**
 * Optional great-circle travel-time planner for verified, single-edge river
 * transfers. Velocity and seconds per simulation tick are caller supplied;
 * this is NOT a calibrated hydraulic velocity, a flood hydrograph, or a
 * guarantee that a sender has actually debited its water.
 *
 * Every journey is checked against the complete caller-verified spherical
 * atlas before being admitted to the existing durable timed-transit ledger.
 */
public final class HydrologyTimedBoundaryTravelV2 {
    private HydrologyTimedBoundaryTravelV2() {}

    public record Journey(String id, GlobalHydrologyAddressV2.Sample source,
            GlobalHydrologyAddressV2.Sample destination, long departureTick,
            double volumeM3, double velocityMetersPerSecond) {
        public Journey {
            Objects.requireNonNull(source);
            Objects.requireNonNull(destination);
            if (departureTick < 0 || !Double.isFinite(velocityMetersPerSecond) ||
                velocityMetersPerSecond <= 0)
                throw new IllegalArgumentException("Invalid travel departure or velocity");
            // Share the durable ledger's ID and volume validation.
            new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                id,source,destination,departureTick,volumeM3);
        }
    }

    public record Plan(List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> edges,
                       Map<String,Double> distancesMeters) {
        public Plan {
            edges = List.copyOf(Objects.requireNonNull(edges));
            distancesMeters = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(distancesMeters)));
        }
    }

    /**
     * Travel takes at least one whole tick. Fractional tick durations round
     * upward; due ticks are absolute, so unloading does not restart the clock.
     * Duplicate identical journeys are idempotent, but conflicting reuse of
     * an ID (including velocity or departure changes) is rejected.
     */
    public static Plan plan(HydrologySphericalRouteAtlasV2 atlas,
            Collection<Journey> journeys, double radiusMeters,
            double secondsPerTick) {
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(journeys);
        HydrologySphericalCellAreaV2.planetArea(radiusMeters);
        if (!Double.isFinite(secondsPerTick) || secondsPerTick <= 0)
            throw new IllegalArgumentException("Invalid simulation tick duration");
        var unique = new TreeMap<String,Journey>();
        for (var journey : journeys) {
            Objects.requireNonNull(journey);
            var prior = unique.putIfAbsent(journey.id(),journey);
            if (prior != null && !prior.equals(journey))
                throw new IllegalArgumentException("Conflicting timed river journey");
        }
        var edges = new ArrayList<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
        var distances = new TreeMap<String,Double>();
        for (var journey : unique.values()) {
            double angular = HydrologySphericalTopologyV2.angularDistance(
                journey.source(),journey.destination());
            double distance = radiusMeters * angular;
            double seconds = distance / journey.velocityMetersPerSecond();
            double ticks = StrictMath.ceil(seconds / secondsPerTick);
            if (!Double.isFinite(distance) || !(distance > 0) ||
                !Double.isFinite(seconds) || !Double.isFinite(ticks) ||
                ticks >= (double)Long.MAX_VALUE)
                throw new IllegalArgumentException("River travel time overflow");
            long delay = Math.max(1L,(long)ticks);
            long due;
            try {
                due = Math.addExact(journey.departureTick(),delay);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("River due tick overflow",ex);
            }
            edges.add(new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                journey.id(),journey.source(),journey.destination(),due,
                journey.volumeM3()));
            distances.put(journey.id(),distance);
        }
        // Validate the entire batch before returning anything to a caller.
        HydrologyVerifiedTimedBoundaryV2.verify(atlas,edges);
        return new Plan(edges,distances);
    }
}
