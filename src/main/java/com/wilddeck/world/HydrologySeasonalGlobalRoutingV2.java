package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in bridge from independently audited regional seasonal runoff to a
 * caller-supplied, geographically addressed drainage network. Known but
 * unloaded vertices are routing nodes with ZERO local water: this never
 * invents precipitation or duplicates water at shared region boundaries.
 *
 * The caller must supply trustworthy routes and independently verified global
 * mouths. Missing routes remain unresolved frontiers, not ocean outlets.
 * This is a volume/rate calculation, not durable receipt delivery, automatic
 * terrain loading, lake storage or a physically timed flood-wave solver.
 */
public final class HydrologySeasonalGlobalRoutingV2 {
    private HydrologySeasonalGlobalRoutingV2() {}

    public record Result(HydrologyFlowGraphV2.Result drainage,
                         HydrologyConservationV2.Budget budget,
                         Map<GlobalHydrologyAddressV2.Sample,
                             HydrologyGlobalCatchmentsV2.CatchmentId> catchments,
                         Map<GlobalHydrologyAddressV2.Sample,
                             HydrologyGlobalCatchmentsV2.ReachId> reaches,
                         Map<GlobalHydrologyAddressV2.Sample,Double> outletM3PerSecond,
                         Map<GlobalHydrologyAddressV2.Sample,Double> frontierM3PerSecond,
                         double durationSeconds) {
        public Result {
            Objects.requireNonNull(drainage);
            Objects.requireNonNull(budget);
            catchments = sorted(catchments);
            reaches = sorted(reaches);
            outletM3PerSecond = sorted(outletM3PerSecond);
            frontierM3PerSecond = sorted(frontierM3PerSecond);
            if (!Double.isFinite(durationSeconds) || durationSeconds <= 0)
                throw new IllegalArgumentException("Invalid routing duration");
        }
        private static <T> Map<GlobalHydrologyAddressV2.Sample,T> sorted(
                Map<GlobalHydrologyAddressV2.Sample,T> source) {
            var result = new TreeMap<GlobalHydrologyAddressV2.Sample,T>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            result.putAll(Objects.requireNonNull(source));
            return Collections.unmodifiableMap(result);
        }
    }

    /**
     * Route one season through a known physical network, including vertices
     * that are not loaded as seasonal regions. Each seasonal source must have
     * exactly one region owner; each network vertex must have one physical
     * identity and a same-resolution adjacent downstream edge. No inferred
     * outlets or cross-LOD area remapping are permitted.
     */
    public static Result route(HydrologySeasonalRegionBudgetV2.Result seasonal,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream,
            Set<GlobalHydrologyAddressV2.Sample> verifiedGlobalMouths) {
        Objects.requireNonNull(seasonal);
        Objects.requireNonNull(knownDownstream);
        Objects.requireNonNull(verifiedGlobalMouths);
        double seconds = seasonal.durationDays() * 86400.0;
        if (!Double.isFinite(seconds) || seconds <= 0)
            throw new IllegalArgumentException("Invalid season duration in seconds");

        var runoff = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var source : seasonal.season().runoff()) {
            if (!HydrologySphericalTopologyV2.isCanonical(source.address()) ||
                runoff.putIfAbsent(source.address(),source.localWater()) != null)
                throw new IllegalArgumentException("Duplicate or noncanonical seasonal runoff");
        }
        if (runoff.isEmpty())
            throw new IllegalArgumentException("Empty seasonal runoff");
        // Audit the named-region partition, not just the global runoff list.
        var grouped = new HashSet<GlobalHydrologyAddressV2.Sample>();
        for (var region : seasonal.runoffByRegion().entrySet()) {
            if (region.getKey() == null || region.getKey().isBlank())
                throw new IllegalArgumentException("Invalid seasonal region name");
            for (var source : region.getValue()) {
                var amount = runoff.get(source.address());
                if (amount == null || Double.compare(amount,source.localWater()) != 0 ||
                    !grouped.add(source.address()))
                    throw new IllegalArgumentException("Duplicated or altered regional runoff");
            }
        }
        if (!grouped.equals(runoff.keySet()))
            throw new IllegalArgumentException("Seasonal runoff has no unique region owner");
        double seasonalTotal = seasonal.season().runoffVolume();
        double sourceTotal = 0;
        for (double value : runoff.values()) {
            sourceTotal += value;
            if (!Double.isFinite(sourceTotal))
                throw new IllegalArgumentException("Seasonal runoff overflow");
        }
        if (!Double.isFinite(seasonalTotal) || seasonalTotal < 0 ||
            Math.abs(sourceTotal-seasonalTotal) > Math.max(1e-8,seasonalTotal*1e-10))
            throw new IllegalArgumentException("Seasonal runoff total mismatch");
        if (!knownDownstream.keySet().containsAll(runoff.keySet()))
            throw new IllegalArgumentException("Missing known seasonal drainage vertex");
        if (!knownDownstream.keySet().containsAll(verifiedGlobalMouths))
            throw new IllegalArgumentException("Unknown verified global mouth");

        var nodes = new ArrayList<HydrologyFlowGraphV2.Node>();
        var reaches = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyGlobalCatchmentsV2.ReachId>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        var physical = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var first = runoff.firstKey();
        var ordered = new TreeMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        ordered.putAll(knownDownstream);
        for (var entry : ordered.entrySet()) {
            var address = Objects.requireNonNull(entry.getKey());
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                address.level() != first.level() ||
                address.divisions() != first.divisions() ||
                !physical.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Ambiguous global drainage owner");
            var next = entry.getValue();
            boolean mouth = verifiedGlobalMouths.contains(address);
            if (mouth && next != null)
                throw new IllegalArgumentException("Global mouth has downstream");
            if (next != null) {
                if (!HydrologySphericalTopologyV2.isCanonical(next) ||
                    !HydrologySphericalTopologyV2.adjacent(address,next))
                    throw new IllegalArgumentException("Invalid global drainage edge");
                reaches.put(address,new HydrologyGlobalCatchmentsV2.ReachId(
                    HydrologyGlobalCatchmentsV2.coordinate(address),
                    HydrologyGlobalCatchmentsV2.coordinate(next)));
            }
            nodes.add(new HydrologyFlowGraphV2.Node(address,
                runoff.getOrDefault(address,0.0),next,mouth));
        }
        var solved = HydrologyFlowGraphV2.solve(nodes);
        var budget = HydrologyConservationV2.verify(nodes,solved);
        if (Math.abs(budget.input()-seasonalTotal) >
                Math.max(1e-8,seasonalTotal*1e-10))
            throw new IllegalArgumentException("Seasonal routing injected water");
        var catchments = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyGlobalCatchmentsV2.CatchmentId>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : solved.resolvedCatchments().entrySet())
            catchments.put(entry.getKey(),new HydrologyGlobalCatchmentsV2.CatchmentId(
                HydrologyGlobalCatchmentsV2.coordinate(entry.getValue())));
        return new Result(solved,budget,catchments,reaches,
            rates(solved.terminalOutflow(),seconds),
            rates(solved.pendingOutflow(),seconds),seconds);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> rates(
            Map<GlobalHydrologyAddressV2.Sample,Double> volumes,double seconds) {
        var rates = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : volumes.entrySet()) {
            double rate = entry.getValue()/seconds;
            if (!Double.isFinite(rate) || rate < 0)
                throw new IllegalArgumentException("Invalid mean discharge rate");
            rates.put(entry.getKey(),rate);
        }
        return rates;
    }
}
