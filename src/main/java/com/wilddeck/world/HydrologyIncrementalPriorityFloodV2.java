package com.wilddeck.world;

import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

        /**
         * Attach ONLY globally owned local water to settled routes. Duplicate
         * shared vertices, seam aliases and unknown/unsettled sources are
         * rejected instead of double-counted or silently discarded.
         *
         * A partial snapshot is not a complete catchment budget: water from
         * undiscovered terrain and future cross-region receipts is absent.
         */
        public List<HydrologyFlowGraphV2.Node> ownedRoutes(
                Collection<HydrologyGlobalSlopeRouterV2.Source> ownedSources) {
            Objects.requireNonNull(ownedSources);
            var water = new TreeMap<GlobalHydrologyAddressV2.Sample, Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for (var source : ownedSources) {
                Objects.requireNonNull(source);
                var address = source.address();
                if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                    !settled.containsKey(address))
                    throw new IllegalArgumentException("Unsettled or noncanonical water source");
                if (water.putIfAbsent(address, source.localWater()) != null)
                    throw new IllegalArgumentException("Duplicate owned water vertex");
            }
            var nodes = new ArrayList<HydrologyFlowGraphV2.Node>();
            for (var entry : settled.entrySet()) {
                var route = entry.getValue();
                nodes.add(new HydrologyFlowGraphV2.Node(
                    entry.getKey(), water.getOrDefault(entry.getKey(), 0.0),
                    route.downstream(), route.downstream() == null));
            }
            return List.copyOf(nodes);
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

    /**
     * Replay checkpoint for a fixed, immutable terrain revision. Its digest
     * covers settled routes AND the discovered best frontier. A checkpoint
     * does not assert that the caller's terrain revision is truthful.
     */
    public record Checkpoint(String terrainRevision,
                             Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets,
                             int settledCount, String stateSha256) {
        public Checkpoint {
            if (terrainRevision == null || terrainRevision.isBlank() ||
                terrainRevision.length() > 256 ||
                settledCount < 0 || settledCount > MAX_SAMPLES ||
                stateSha256 == null || !stateSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid flood checkpoint");
            Objects.requireNonNull(verifiedOutlets);
            var sorted = new TreeSet<GlobalHydrologyAddressV2.Sample>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for (var outlet : verifiedOutlets) {
                Objects.requireNonNull(outlet);
                if (!HydrologySphericalTopologyV2.isCanonical(outlet))
                    throw new IllegalArgumentException("Noncanonical checkpoint outlet");
                if (!sorted.isEmpty() &&
                    (outlet.level() != sorted.first().level() ||
                     outlet.divisions() != sorted.first().divisions()))
                    throw new IllegalArgumentException("Mixed checkpoint resolutions");
                sorted.add(outlet);
            }
            if (sorted.isEmpty() || sorted.size() > MAX_SAMPLES)
                throw new IllegalArgumentException("Invalid checkpoint outlets");
            verifiedOutlets = Collections.unmodifiableSet(sorted);
        }
    }

    private record Candidate(GlobalHydrologyAddressV2.Sample address,
                             double spill, int steps) {}

    public static final class Session {
        private final HydrologyGlobalSlopeRouterV2.Elevations elevations;
        private final Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets;
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
            this.verifiedOutlets = Collections.unmodifiableSet(sorted);
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

        /** Save only at a successful batch boundary; never during expansion. */
        public Checkpoint checkpoint(String terrainRevision) {
            return new Checkpoint(terrainRevision, verifiedOutlets,
                settled.size(), stateDigest());
        }

        private String stateDigest() {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                // Stable ordering and numeric encodings, independent of map insertion.
                for (int kind = 0; kind < 2; kind++) {
                    var entries = kind == 0 ? best : settled;
                    digest.update((byte) kind);
                    for (var entry : entries.entrySet()) {
                        append(digest, entry.getKey());
                        var route = entry.getValue();
                        append(digest, Double.doubleToLongBits(route.spillElevation()));
                        append(digest, route.steps());
                        if (route.downstream() == null) digest.update((byte) 0);
                        else {
                            digest.update((byte) 1);
                            append(digest, route.downstream());
                        }
                        append(digest, route.catchment().outlet());
                    }
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 unavailable", ex);
            }
        }

        private static void append(MessageDigest digest, long value) {
            for (int shift = 56; shift >= 0; shift -= 8)
                digest.update((byte) (value >>> shift));
        }

        private static void append(MessageDigest digest,
                                   GlobalHydrologyAddressV2.Sample a) {
            append(digest, a.level());
            append(digest, a.divisions());
            append(digest, a.x());
            append(digest, a.y());
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

    /**
     * Reconstruct a checkpoint by deterministic replay, checking both settled
     * topology and discovered frontier. The elevation oracle must represent
     * the SAME immutable terrain revision as the checkpoint. Replaying can
     * cost O(settled vertices); it avoids trusting serialized queue objects.
     */
    public static Session resume(Checkpoint checkpoint, String terrainRevision,
                                 HydrologyGlobalSlopeRouterV2.Elevations elevations) {
        Objects.requireNonNull(checkpoint);
        if (!checkpoint.terrainRevision().equals(terrainRevision))
            throw new IllegalArgumentException("Flood terrain revision mismatch");
        var session = start(checkpoint.verifiedOutlets(), elevations);
        if (checkpoint.settledCount() > 0)
            session.advance(checkpoint.settledCount());
        if (session.settled.size() != checkpoint.settledCount() ||
            !session.stateDigest().equals(checkpoint.stateSha256()))
            throw new IllegalArgumentException("Flood checkpoint replay mismatch");
        return session;
    }

    private static double checked(double value) {
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("Nonfinite spherical elevation");
        return value;
    }
}
