package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in, noncommitting seasonal water budget for several physical regions.
 * Shared geographic vertices have exactly one owner, even when two region
 * windows include the same edge or pole. All cells must use one spherical
 * sampling resolution: mixed-LOD cell areas cannot be summed without an
 * explicit nonoverlapping remapping.
 *
 * The entire weather inventory and planetary areas are checked before any
 * snow/soil transition. Only the resulting audited runoff may be injected
 * into downstream river routing; precipitation must not be injected again.
 * This does not load regions, discover drainage routes, persist checkpoints, or provide
 * globally atomic seasonal advancement.
 */
public final class HydrologySeasonalRegionBudgetV2 {
    private HydrologySeasonalRegionBudgetV2() {}

    public record Result(HydrologySnowSoilStateV2.Step season,
            Map<String,List<HydrologyGlobalSlopeRouterV2.Source>> runoffByRegion,
            double ownedAreaM2, double durationDays) {
        public Result {
            Objects.requireNonNull(season);
            Objects.requireNonNull(runoffByRegion);
            var ordered = new TreeMap<String,List<HydrologyGlobalSlopeRouterV2.Source>>();
            for (var entry : runoffByRegion.entrySet())
                ordered.put(Objects.requireNonNull(entry.getKey()),
                    List.copyOf(Objects.requireNonNull(entry.getValue())));
            runoffByRegion = Collections.unmodifiableMap(ordered);
            if (!Double.isFinite(ownedAreaM2) || ownedAreaM2 <= 0 ||
                !Double.isFinite(durationDays) || durationDays <= 0)
                throw new IllegalArgumentException("Invalid regional seasonal budget");
        }

        /**
         * Route audited seasonal runoff through a caller-supplied global
         * drainage network that may include known but unloaded vertices.
         * Only independently verified global mouths receive stable catchment
         * IDs. Unknown terrain remains pending; no extra water is injected.
         */
        public HydrologySeasonalGlobalRoutingV2.Result routeKnownNetwork(
                Map<GlobalHydrologyAddressV2.Sample,
                    GlobalHydrologyAddressV2.Sample> knownDownstream,
                Set<GlobalHydrologyAddressV2.Sample> verifiedGlobalMouths) {
            return HydrologySeasonalGlobalRoutingV2.route(
                this,knownDownstream,verifiedGlobalMouths);
        }

        /**
         * Route exactly the audited regional runoff sources through one
         * canonical flow graph. A downstream address outside the loaded
         * seasonal inventory is retained as a pending frontier, not invented
         * as an ocean mouth. Only explicitly verified outlets are terminals.
         * Geographic sample/catchment identities remain stable across the
         * named region partition. This does not resolve unloaded terrain.
         */
        public Routed route(Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> downstream,
                Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
            Objects.requireNonNull(downstream);
            Objects.requireNonNull(verifiedOutlets);
            var owned = new HashSet<GlobalHydrologyAddressV2.Sample>();
            for (var source : season.runoff())
                owned.add(source.address());
            if (!owned.containsAll(verifiedOutlets) ||
                !owned.containsAll(downstream.keySet()))
                throw new IllegalArgumentException("Unknown seasonal routing owner");
            for (var entry : downstream.entrySet()) {
                if (entry.getValue() == null ||
                    !HydrologySphericalTopologyV2.isCanonical(entry.getValue()) ||
                    verifiedOutlets.contains(entry.getKey()))
                    throw new IllegalArgumentException("Invalid seasonal downstream edge");
            }
            var nodes = new ArrayList<HydrologyFlowGraphV2.Node>();
            for (var source : season.runoff()) {
                var address = source.address();
                nodes.add(new HydrologyFlowGraphV2.Node(address,
                    source.localWater(),downstream.get(address),
                    verifiedOutlets.contains(address)));
            }
            var solved = HydrologyFlowGraphV2.solve(nodes);
            return new Routed(solved,HydrologyConservationV2.verify(nodes,solved));
        }
    }

    public record Routed(HydrologyFlowGraphV2.Result drainage,
            HydrologyConservationV2.Budget budget) {
        public Routed {
            Objects.requireNonNull(drainage);
            Objects.requireNonNull(budget);
        }
    }

    /**
     * Assemble one audited seasonal transition across disjoint named regions.
     * Rejects overlapping cells, mixed sampling resolutions, inconsistent
     * season durations, forged cell areas and missing persisted cells.
     */
    public static Result advance(HydrologySnowSoilStateV2.State previous,
            Map<String,Map<GlobalHydrologyAddressV2.Sample,
                HydrologySnowSoilStateV2.Forcing>> regions,
            double radiusMeters) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(regions);
        double planetArea = HydrologySphericalCellAreaV2.planetArea(radiusMeters);
        if (regions.isEmpty())
            throw new IllegalArgumentException("Empty seasonal region inventory");

        var sortedRegions = new TreeMap<String,Map<GlobalHydrologyAddressV2.Sample,
            HydrologySnowSoilStateV2.Forcing>>();
        for (var entry : regions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() ||
                entry.getValue() == null || entry.getValue().isEmpty())
                throw new IllegalArgumentException("Invalid seasonal region");
            sortedRegions.put(entry.getKey(),entry.getValue());
        }

        var weather = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologySnowSoilStateV2.Forcing>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        var owners = new HashMap<GlobalHydrologyAddressV2.Sample,String>();
        var physical = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        Integer level = null, divisions = null;
        Double days = null;
        double area = 0;
        for (var region : sortedRegions.entrySet()) {
            var sorted = new TreeMap<GlobalHydrologyAddressV2.Sample,
                HydrologySnowSoilStateV2.Forcing>(HydrologyFlowGraphV2.ADDRESS_ORDER);
            sorted.putAll(region.getValue());
            for (var entry : sorted.entrySet()) {
                var address = Objects.requireNonNull(entry.getKey());
                var forcing = Objects.requireNonNull(entry.getValue());
                if (!HydrologySphericalTopologyV2.isCanonical(address))
                    throw new IllegalArgumentException("Noncanonical seasonal owner");
                if (level == null) {
                    level = address.level();
                    divisions = address.divisions();
                } else if (level != address.level() ||
                           divisions != address.divisions()) {
                    throw new IllegalArgumentException(
                        "Mixed-resolution seasonal area requires explicit remapping");
                }
                if (days == null) days = forcing.days();
                else if (Double.compare(days,forcing.days()) != 0)
                    throw new IllegalArgumentException("Inconsistent seasonal duration");
                if (!physical.add(HydrologyGlobalCatchmentsV2.coordinate(address)) ||
                    weather.putIfAbsent(address,forcing) != null)
                    throw new IllegalArgumentException("Shared physical seasonal vertex");
                owners.put(address,region.getKey());
                area += forcing.cellArea();
                if (!Double.isFinite(area) ||
                    weather.size() > HydrologySnowSoilStateV2.MAX_CELLS)
                    throw new IllegalArgumentException("Seasonal region area or cell budget exceeded");
            }
        }
        // No arbitrary cell areas: all region budgets must use the same
        // physical spherical area convention as the routing/erosion system.
        HydrologySphericalAreaContractV2.verifyWeather(weather,radiusMeters);
        if (area > planetArea * (1 + 1e-12))
            throw new IllegalArgumentException("Seasonal area exceeds planet surface");
        if (!weather.keySet().containsAll(previous.cells().keySet()))
            throw new IllegalArgumentException("Persisted seasonal cell has no region");

        var season = HydrologySnowSoilStateV2.advanceAudited(previous,weather);
        var grouped = new TreeMap<String,List<HydrologyGlobalSlopeRouterV2.Source>>();
        for (var name : sortedRegions.keySet())
            grouped.put(name,new ArrayList<>());
        for (var source : season.runoff()) {
            var owner = owners.get(source.address());
            if (owner == null)
                throw new IllegalStateException("Seasonal runoff lost physical owner");
            grouped.get(owner).add(source);
        }
        return new Result(season,grouped,area,days);
    }
}
