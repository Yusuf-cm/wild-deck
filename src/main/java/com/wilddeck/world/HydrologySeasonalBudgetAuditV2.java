package com.wilddeck.world;

import java.util.*;

/**
 * Independent SI-volume audit of the canonical per-cell seasonal snow/soil
 * checkpoint transition. Checks that persisted runoff is the only local
 * precipitation-derived river source, and that the depth-based state agrees
 * with the separate volume-based bucket model.
 *
 * Opt-in verification, not a weather generator, storage transaction or
 * calibration of the degree-day approximation. The caller must supply
 * nonoverlapping canonical physical cells and their authoritative areas.
 */
public final class HydrologySeasonalBudgetAuditV2 {
    private HydrologySeasonalBudgetAuditV2() {}

    public record Audit(int cells, double initialM3, double precipitationM3,
                        double evaporationM3, double runoffM3, double storedM3) {}

    public static Audit verify(HydrologySnowSoilStateV2.State previous,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            HydrologySnowSoilStateV2.Step candidate) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(forcing);
        Objects.requireNonNull(candidate);
        if (!candidate.state().world().equals(previous.world()) ||
            !candidate.state().cells().keySet().equals(forcing.keySet()) ||
            !forcing.keySet().containsAll(previous.cells().keySet()) ||
            forcing.isEmpty() || forcing.size() > HydrologySnowSoilStateV2.MAX_CELLS)
            throw new IllegalArgumentException("Seasonal checkpoint cell inventory changed");

        var sources = new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var source : candidate.runoff()) {
            if (sources.putIfAbsent(source.address(),source.localWater()) != null)
                throw new IllegalArgumentException("Duplicate seasonal runoff source");
        }
        if (!sources.keySet().equals(forcing.keySet()))
            throw new IllegalArgumentException("Seasonal runoff source inventory mismatch");

        double initial = 0, precipitation = 0, evaporation = 0, runoff = 0, stored = 0;
        for (var entry : forcing.entrySet()) {
            var address = Objects.requireNonNull(entry.getKey());
            if (!HydrologySphericalTopologyV2.isCanonical(address))
                throw new IllegalArgumentException("Noncanonical seasonal cell");
            var f = Objects.requireNonNull(entry.getValue());
            var old = previous.cells().getOrDefault(address,
                new HydrologySnowSoilStateV2.Cell(0,0));
            var next = candidate.state().cells().get(address);
            double area = f.cellArea();
            var reference = HydrologySeasonalSnowSoilV2.advance(
                new HydrologySeasonalSnowSoilV2.State(
                    volume(old.snowDepth(),area),volume(old.soilDepth(),area)),
                new HydrologySeasonalSnowSoilV2.Forcing(
                    f.precipitationDepth(),f.temperatureC(),
                    f.potentialEvaporationDepth(),area,f.days(),
                    f.degreeDayMeltFactor(),volume(f.soilCapacityDepth(),area)));
            requireClose(volume(next.snowDepth(),area),reference.state().snowM3(),
                "snow storage");
            requireClose(volume(next.soilDepth(),area),reference.state().soilM3(),
                "soil storage");
            requireClose(sources.get(address),reference.runoffM3(),"river runoff");
            initial = add(initial,add(volume(old.snowDepth(),area),
                                      volume(old.soilDepth(),area)));
            precipitation = add(precipitation,
                add(reference.rainM3(),reference.snowfallM3()));
            evaporation = add(evaporation,reference.evaporatedM3());
            runoff = add(runoff,reference.runoffM3());
            stored = add(stored,add(reference.state().snowM3(),
                                    reference.state().soilM3()));
        }
        requireClose(candidate.initialVolume(),initial,"initial volume");
        requireClose(candidate.precipitationVolume(),precipitation,"precipitation");
        requireClose(candidate.evaporationVolume(),evaporation,"evaporation");
        requireClose(candidate.runoffVolume(),runoff,"total runoff");
        requireClose(candidate.storedVolume(),stored,"stored volume");
        requireClose(add(initial,precipitation),
            add(add(evaporation,runoff),stored),"global water balance");
        return new Audit(forcing.size(),initial,precipitation,evaporation,runoff,stored);
    }

    private static double volume(double depth,double area) {
        return finite(depth*area);
    }

    private static double add(double a,double b) {
        return finite(a+b);
    }

    private static double finite(double v) {
        if (!Double.isFinite(v) || v < 0)
            throw new IllegalArgumentException("Seasonal audit volume overflow");
        return v;
    }

    private static void requireClose(double actual,double expected,String name) {
        finite(actual);
        finite(expected);
        double tolerance = Math.max(1e-9,
            Math.max(Math.abs(actual),Math.abs(expected))*1e-10);
        if (Math.abs(actual-expected) > tolerance)
            throw new IllegalArgumentException("Seasonal audit mismatch: "+name);
    }
}
