package com.wilddeck.world;

import java.util.*;

/**
 * Explicit seasonal integration for a single known lake basin. Each season
 * applies inflow and surface evaporation before sediment deposition. Capacity
 * lost to deposition displaces water into overflow in the same season.
 *
 * Caller supplies forcing and must persist the resulting state and route both
 * water and sediment exports. This is not a climate, flood-wave, or globally
 * connected river solver. A fully silted basin remains non-reservoir terrain.
 */
public final class SeasonalLakeEvolutionV2 {
    private SeasonalLakeEvolutionV2() {}

    public record State(Optional<LakeReservoirV2.Basin> basin,
                        double[] bedElevation, double storedWater) {
        public State {
            Objects.requireNonNull(basin);
            Objects.requireNonNull(bedElevation);
            bedElevation = bedElevation.clone();
            if (bedElevation.length == 0)
                throw new IllegalArgumentException("Empty lake terrain");
            for (double bed : bedElevation)
                if (!Double.isFinite(bed))
                    throw new IllegalArgumentException("Nonfinite lake terrain");
            if (!Double.isFinite(storedWater) || storedWater < 0 ||
                (basin.isEmpty() && storedWater != 0) ||
                (basin.isPresent() && (storedWater > basin.get().capacity() ||
                    !Arrays.equals(bedElevation, basin.get().bedElevation()))))
                throw new IllegalArgumentException("Invalid seasonal lake state");
        }
        @Override public double[] bedElevation() { return bedElevation.clone(); }
        public static State initial(LakeReservoirV2.Basin basin, double storedWater) {
            Objects.requireNonNull(basin);
            return new State(Optional.of(basin), basin.bedElevation(), storedWater);
        }
    }

    public record Forcing(double inflow, double evaporationDepth,
                          double sedimentMass, double sedimentDensity) {
        public Forcing {
            if (!Double.isFinite(inflow) || inflow < 0 ||
                !Double.isFinite(evaporationDepth) || evaporationDepth < 0 ||
                !Double.isFinite(sedimentMass) || sedimentMass < 0 ||
                !Double.isFinite(sedimentDensity) || sedimentDensity <= 0)
                throw new IllegalArgumentException("Invalid seasonal forcing");
        }
    }

    /** Legacy explicit evaporation or opt-in inflow-first stage drawdown. */
    public enum EvaporationMode { START_OF_STEP, INFLOW_THEN_EVAPORATION }

    public record Step(State state, double evaporated, double waterOverflow,
                       double depositedSediment, double exportedSediment) {
        public Step {
            Objects.requireNonNull(state);
            if (!Double.isFinite(evaporated) || evaporated < 0 ||
                !Double.isFinite(waterOverflow) || waterOverflow < 0 ||
                !Double.isFinite(depositedSediment) || depositedSediment < 0 ||
                !Double.isFinite(exportedSediment) || exportedSediment < 0)
                throw new IllegalArgumentException("Invalid seasonal lake flux");
        }
    }

    public static Step advance(State state, Forcing forcing) {
        return advance(state, forcing, EvaporationMode.START_OF_STEP);
    }

    public static Step advance(State state, Forcing forcing, EvaporationMode mode) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(forcing);
        Objects.requireNonNull(mode);
        if (state.basin().isEmpty())
            return new Step(state, 0, forcing.inflow(), 0, forcing.sedimentMass());
        var basin = state.basin().get();
        var water = mode == EvaporationMode.INFLOW_THEN_EVAPORATION ?
            LakeReservoirV2.stepInflowThenEvaporation(basin, state.storedWater(),
                forcing.inflow(), forcing.evaporationDepth()) :
            LakeReservoirV2.step(basin, state.storedWater(),
                forcing.inflow(), forcing.evaporationDepth());
        var sediment = LakeSedimentEvolutionV2.deposit(basin, water.volume(),
                                forcing.sedimentMass(), forcing.sedimentDensity());
        double overflow = water.overflow() + sediment.displacedWater();
        if (!Double.isFinite(overflow))
            throw new IllegalArgumentException("Lake overflow");
        return new Step(new State(sediment.basin(), sediment.bedElevation(),
                                  sediment.storedWater()), water.evaporated(), overflow,
                        sediment.depositedSediment(), sediment.exportedSediment());
    }

    /** A deterministic sequence; no caller-owned arrays or state are mutated. */
    public static List<Step> simulate(State initial, List<Forcing> seasons) {
        return simulate(initial, seasons, EvaporationMode.START_OF_STEP);
    }

    /** Opt-in evaporation timing; each season uses the same scheme. */
    public static List<Step> simulate(State initial, List<Forcing> seasons,
                                      EvaporationMode mode) {
        Objects.requireNonNull(initial);
        Objects.requireNonNull(seasons);
        Objects.requireNonNull(mode);
        var steps = new ArrayList<Step>(seasons.size());
        State state = initial;
        for (var forcing : seasons) {
            var step = advance(state, Objects.requireNonNull(forcing), mode);
            steps.add(step);
            state = step.state();
        }
        return List.copyOf(steps);
    }
}
