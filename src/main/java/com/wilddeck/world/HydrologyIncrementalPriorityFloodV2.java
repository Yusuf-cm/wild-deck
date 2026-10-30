package com.wilddeck.world;

import java.util.*;

/**
 * Incremental, outlet-seeded priority flood over canonical spherical samples.
 * Terrain is sampled on demand, including neighboring regions not loaded as
 * tiles. The priority is (minimum spill elevation, shortest route, address).
 *
 * Only SETTLED routes are published. Their downstream parents have already
 * settled and ultimately reach a caller-verified outlet; an unvisited or
 * merely discovered vertex is never declared a catchment or ocean.
 *
 * This is an in-memory, single-resolution planning session, not a durable
 * frontier checkpoint or a time-dependent lake discharge simulation.
 * Spill paths describe possible overflow once lake storage reaches its sill.
 */
public final class HydrologyIncrementalPriorityFloodV2 {
    private HydrologyIncrementalPriorityFloodV2() {}
    public static final int MAX_SAMPLES = 100_000;

    public record Route(double spillElevation, int steps,
                        GlobalHydrologyAddressV2.Sample downstream,
                        HydrologyRiverIdentityV2.CatchmentId catchment) {
        public Route {
            if (!Double.isFinite(spillElevation) || steps < 0 ||
                catchment == null || (steps == 0) != (downstream == null))
                throw new IllegalArgumentException("Invalid settled spill route");
        }
    }

    public record Snapshot(
            Map<GlobalHydrologyAddressV2.Sample, Route> settled,
            boolean complete) {
        public Snapshot {
            Objects.requireNonNull(settled);
            var copy = new TreeMap<GlobalHydrologyAddressV2.Sample, Route>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for (var entry : settled.entrySet()) {
                Objects.requireNonNull(entry.getKey());
                Objects.requireNonNull(entry.getValue());
                if (copy.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                    throw new IllegalArgumentException("Duplicate settled address");
            }
            settled = Collections.unmodifiableMap(copy);
        }

        /** Zero-water topology only. Caller owns rainfall and receipt budgets. */
        public List<HydrologyFlowGraphV2.Node> knownRoutes() {
            var nodes = new ArrayList<HydrologyFlowGraphV2.Node>();
            for (var entry : settled.entrySet()) {
                var route = entry.getValue();
                if (route.downstream() != null &&
                    !settled.containsKey(route.downstream()))
                    throw new IllegalStateException("Unsettled downstream parent");
                nodes.add(new HydrologyFlowGraphV2.Node(
                    entry.getKey(), 0, route.downstream(),
                    route.downstream() == null));
            }
            return List.copyOf(nodes);
        }
    }

    private record Candidate(GlobalHydrologyAddressV2.Sample address,
                             double spill, int steps) {}

    public static final class Session {
        private final HydrologyGlobalSlopeRouterV2.Elevations elevations;
        private final TreeMap<GlobalHydrologyAddressV2.Sample, Route> best =
            new TreeMap<>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        private final TreeMap<GlobalHydrologyAddressV2.Sample, Route> settled =
            new TreeMap<>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        private final PriorityQueue<Candidate> frontier = new PriorityQueue<>(
            Comparator.comparingDouble(Candidate::spill)
                .thenComparingInt(Candidate::steps)
                .thenComparing(Candidate::address,
                    HydrologyFlowGraphV2.ADDRESS_ORDER));

        private Session(Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets,
                        HydrologyGlobalSlopeRouterV2.Elevations elevations) {
            this.elevations = Objects.requireNonNull(elevations);
            Objects.requireNonNull(verifiedOutlets);
            if (verifiedOutlets.isEmpty())
                throw new IllegalArgumentException("At least one verified outlet required");
            var sorted = new TreeSet<GlobalHydrologyAddressV2.Sample>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for (var outlet : verifiedOutlets) {
                Objects.requireNonNull(outlet);
                if (!HydrologySphericalTopologyV2.isCanonical(outlet))
                    throw new IllegalArgumentException("Noncanonical verified outlet");
                if (!sorted.isEmpty()) {
                    var first = sorted.first();
                    if (first.level() != outlet.level() ||
                        first.divisions() != outlet.divisions())
                        throw new IllegalArgumentException("Mixed outlet resolutions");
                }
                sorted.add(outlet);
            }
            for (var outlet : sorted) {
                var route = new Route(checked(elevations.at(outlet)), 0, null,
                    new HydrologyRiverIdentityV2.CatchmentId(outlet));
                best.put(outlet, route);
                frontier.add(new Candidate(outlet, route.spillElevation(), 0));
            }
        }

        /**
         * Advance at most maxSettlements finalized vertices. Repeated batches
         * yield the same routes as one uninterrupted solve on fixed terrain.
         * The caller must not change the elevation oracle during a session.
         */
        public Snapshot advance(int maxSettlements) {
            if (maxSettlements < 1 || maxSettlements > MAX_SAMPLES)
                throw new IllegalArgumentException("Invalid flood batch budget");
            int advanced = 0;
            while (advanced < maxSettlements && !frontier.isEmpty()) {
                var candidate = frontier.remove();
                var route = best.get(candidate.address());
                if (settled.containsKey(candidate.address()) ||
                    Double.compare(route.spillElevation(), candidate.spill()) != 0 ||
                    route.steps() != candidate.steps())
                    continue;
                if (settled.size() == MAX_SAMPLES)
                    throw new IllegalArgumentException("Flood sample budget exceeded");
                settled.put(candidate.address(), route);
                advanced++;
                for (var neighbor : HydrologySphericalTopologyV2.neighbors(
                        candidate.address())) {
                    if (settled.containsKey(neighbor)) continue;
                    double spill = Math.max(route.spillElevation(),
                        checked(elevations.at(neighbor)));
                    int steps = Math.addExact(route.steps(), 1);
                    var previous = best.get(neighbor);
                    int cost = previous == null ? -1 :
                        Double.compare(spill, previous.spillElevation());
                    boolean better = previous == null || cost < 0 ||
                        (cost == 0 && (steps < previous.steps() ||
                        (steps == previous.steps() &&
                        HydrologyFlowGraphV2.ADDRESS_ORDER.compare(
                            candidate.address(), previous.downstream()) < 0)));
                    if (better) {
                        var next = new Route(spill, steps, candidate.address(),
                            route.catchment());
                        best.put(neighbor, next);
                        if (best.size() > MAX_SAMPLES)
                            throw new IllegalArgumentException("Flood frontier budget exceeded");
                        if (previous == null || cost < 0 || steps < previous.steps())
                            frontier.add(new Candidate(neighbor, spill, steps));
                    }
                }
            }
            return snapshot();
        }

        public Snapshot snapshot() {
            return new Snapshot(settled, frontier.isEmpty());
        }
    }

    public static Session start(
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets,
            HydrologyGlobalSlopeRouterV2.Elevations elevations) {
        return new Session(verifiedOutlets, elevations);
    }

    private static double checked(double value) {
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("Nonfinite spherical elevation");
        return value;
    }
}
