package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Opt-in seasonal catchment water budget in SI units (m, m2, m3, days, C).
 * Rain/snow partition is a freezing-point approximation; positive-temperature
 * degree-day melt is bounded by existing snow, and evapotranspiration cannot
 * remove more liquid water than exists. Excess soil water becomes runoff.
 *
 * No hidden groundwater, inter-region flux, lake storage or new rainfall is
 * invented. The caller must provide calibrated meteorology and a physically
 * owned catchment area. This is NOT a global climate or routing scheduler.
 */
public final class HydrologySeasonalSnowSoilV2 {
    private HydrologySeasonalSnowSoilV2() {}

    public record State(double snowM3, double soilM3) {
        public State {
            nonnegative(snowM3,"snow");
            nonnegative(soilM3,"soil");
        }
    }

    public record Forcing(double precipitationDepthM, double airTemperatureC,
            double potentialEvaporationDepthM, double areaM2, double durationDays,
            double meltDepthPerDegreeDayM, double soilCapacityM3) {
        public Forcing {
            nonnegative(precipitationDepthM,"precipitation");
            if (!Double.isFinite(airTemperatureC))
                throw new IllegalArgumentException("Invalid temperature");
            nonnegative(potentialEvaporationDepthM,"potential evaporation");
            if (!Double.isFinite(areaM2) || areaM2 <= 0)
                throw new IllegalArgumentException("Invalid catchment area");
            if (!Double.isFinite(durationDays) || durationDays <= 0)
                throw new IllegalArgumentException("Invalid duration");
            nonnegative(meltDepthPerDegreeDayM,"degree-day melt factor");
            nonnegative(soilCapacityM3,"soil capacity");
        }
    }

    public record Step(State state, double rainM3, double snowfallM3,
            double snowmeltM3, double evaporatedM3, double runoffM3,
            double conservationResidualM3) {
        public Step {
            Objects.requireNonNull(state);
            nonnegative(rainM3,"rain");
            nonnegative(snowfallM3,"snowfall");
            nonnegative(snowmeltM3,"snowmelt");
            nonnegative(evaporatedM3,"evaporation");
            nonnegative(runoffM3,"runoff");
            if (!Double.isFinite(conservationResidualM3))
                throw new IllegalArgumentException("Nonfinite conservation residual");
        }
    }

    private static double nonnegative(double value,String name) {
        if (!Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid "+name);
        return value;
    }

    private static double add(double left,double right) {
        double result = left + right;
        if (!Double.isFinite(result) || result < 0)
            throw new IllegalArgumentException("Water volume overflow");
        return result;
    }

    private static double multiply(double left,double right) {
        double result = left * right;
        if (!Double.isFinite(result) || result < 0)
            throw new IllegalArgumentException("Water forcing overflow");
        return result;
    }

    private static BigDecimal decimal(double amount) {
        return BigDecimal.valueOf(amount);
    }

    /** Advance one season; no state or input is mutated. */
    public static Step advance(State previous,Forcing forcing) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(forcing);
        if (previous.soilM3() > forcing.soilCapacityM3())
            throw new IllegalArgumentException("Soil storage exceeds capacity");

        double precipitation = multiply(forcing.precipitationDepthM(),forcing.areaM2());
        boolean frozen = forcing.airTemperatureC() <= 0;
        double snowfall = frozen ? precipitation : 0;
        double rain = frozen ? 0 : precipitation;
        double snow = add(previous.snowM3(),snowfall);
        double melt = 0;
        if (!frozen && snow > 0) {
            double depth = multiply(forcing.meltDepthPerDegreeDayM(),
                forcing.airTemperatureC());
            double potential = multiply(multiply(depth,forcing.durationDays()),
                forcing.areaM2());
            melt = Math.min(snow,potential);
            snow -= melt;
        }
        double liquid = add(add(previous.soilM3(),rain),melt);
        double evaporationDemand = multiply(
            forcing.potentialEvaporationDepthM(),forcing.areaM2());
        double evaporated = Math.min(liquid,evaporationDemand);
        double remaining = liquid - evaporated;
        double soil = Math.min(remaining,forcing.soilCapacityM3());
        double runoff = remaining - soil;
        var left = decimal(previous.snowM3()).add(decimal(previous.soilM3()))
            .add(decimal(precipitation));
        var right = decimal(snow).add(decimal(soil))
            .add(decimal(evaporated)).add(decimal(runoff));
        double totalInput = left.doubleValue();
        double totalOutput = right.doubleValue();
        if (!Double.isFinite(totalInput) || !Double.isFinite(totalOutput))
            throw new IllegalArgumentException("Seasonal total water overflow");
        double residual = left.subtract(right).doubleValue();
        double tolerance = Math.max(1e-9,
            Math.max(totalInput,totalOutput)*1e-12);
        if (!Double.isFinite(residual) || Math.abs(residual) > tolerance)
            throw new IllegalArgumentException("Seasonal water conservation failed");
        return new Step(new State(snow,soil),rain,snowfall,melt,
            evaporated,runoff,residual);
    }

    /** Sequential deterministic seasons, retaining snow and soil water. */
    public static List<Step> simulate(State initial,List<Forcing> seasons) {
        Objects.requireNonNull(initial);
        Objects.requireNonNull(seasons);
        var result = new ArrayList<Step>(seasons.size());
        State current = initial;
        for (var forcing : seasons) {
            var step = advance(current,Objects.requireNonNull(forcing));
            result.add(step);
            current = step.state();
        }
        return List.copyOf(result);
    }
}
