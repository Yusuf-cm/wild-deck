package com.wilddeck.world;

import java.util.*;

/**
 * Stable geographic identities for a fully supplied, verified drainage forest.
 *
 * The caller must include every downstream vertex, even when the corresponding
 * tile is unloaded, and independently verify each terminal as a true global
 * mouth. A regional boundary is NOT automatically a global catchment outlet.
 * This class does not discover terrain, authenticate mouths or route water.
 */
public final class HydrologyGlobalCatchmentsV2 {
    private HydrologyGlobalCatchmentsV2() {}

    private static final Comparator<GlobalHydrologyAddressV2.Sample> ADDRESS_ORDER =
        Comparator.comparingInt(GlobalHydrologyAddressV2.Sample::level)
            .thenComparingInt(GlobalHydrologyAddressV2.Sample::divisions)
            .thenComparingLong(GlobalHydrologyAddressV2.Sample::x)
            .thenComparingLong(GlobalHydrologyAddressV2.Sample::y);

    /** Exact normalized spherical grid coordinates, independent of resolution. */
    public record Coordinate(long lonNumerator, long lonDenominator,
                             long latNumerator, long latDenominator) {
        public Coordinate {
            if (lonNumerator < 0 || lonDenominator < 1 ||
                lonNumerator >= lonDenominator || latNumerator < 0 ||
                latDenominator < 1 || latNumerator > latDenominator ||
                gcd(lonNumerator, lonDenominator) != 1 ||
                gcd(latNumerator, latDenominator) != 1 ||
                ((latNumerator == 0 || latNumerator == latDenominator) &&
                    lonNumerator != 0))
                throw new IllegalArgumentException("Noncanonical geographic coordinate");
        }

        public String stableKey() {
            return lonNumerator + "/" + lonDenominator + ":" +
                   latNumerator + "/" + latDenominator;
        }
    }

    public record CatchmentId(Coordinate mouth) {
        public CatchmentId { Objects.requireNonNull(mouth); }
        public String stableKey() { return "catchment:v2:" + mouth.stableKey(); }
    }

    /** Geographic edge identity. Changes when a river changes its actual edge. */
    public record ReachId(Coordinate upstream, Coordinate downstream) {
        public ReachId {
            Objects.requireNonNull(upstream);
            Objects.requireNonNull(downstream);
            if (upstream.equals(downstream))
                throw new IllegalArgumentException("Zero-length river reach");
        }
        public String stableKey() {
            return "river-reach:v2:" + upstream.stableKey() + "->" +
                   downstream.stableKey();
        }
    }

    public record Result(Map<GlobalHydrologyAddressV2.Sample,CatchmentId> catchments,
                         Map<GlobalHydrologyAddressV2.Sample,ReachId> reaches) {
        public Result {
            Objects.requireNonNull(catchments);
            Objects.requireNonNull(reaches);
            var orderedCatchments = new TreeMap<GlobalHydrologyAddressV2.Sample,CatchmentId>(ADDRESS_ORDER);
            orderedCatchments.putAll(catchments);
            var orderedReaches = new TreeMap<GlobalHydrologyAddressV2.Sample,ReachId>(ADDRESS_ORDER);
            orderedReaches.putAll(reaches);
            catchments = Collections.unmodifiableMap(orderedCatchments);
            reaches = Collections.unmodifiableMap(orderedReaches);
        }
    }

    public static Coordinate coordinate(GlobalHydrologyAddressV2.Sample sample) {
        Objects.requireNonNull(sample);
        long width = (2L << sample.level()) * sample.divisions();
        long height = (1L << sample.level()) * sample.divisions();
        if (sample.x() == width ||
            ((sample.y() == 0 || sample.y() == height) && sample.x() != 0))
            throw new IllegalArgumentException("Noncanonical seam or polar sample");
        long xDivisor = gcd(sample.x(), width);
        long yDivisor = gcd(sample.y(), height);
        return new Coordinate(sample.x() / xDivisor, width / xDivisor,
                              sample.y() / yDivisor, height / yDivisor);
    }

    /**
     * Resolve complete paths to independently verified global mouths.
     * A null downstream is legal only at a verified mouth. Every referenced
     * downstream must be present, including nodes in unloaded regions.
     * Duplicate physical ownership across levels, seam aliases, unknown
     * frontiers and cycles are rejected rather than assigned fake identities.
     */
    public static Result resolve(
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> verifiedGlobalMouths) {
        Objects.requireNonNull(downstream);
        Objects.requireNonNull(verifiedGlobalMouths);
        if (downstream.isEmpty() || verifiedGlobalMouths.isEmpty())
            throw new IllegalArgumentException("Empty global drainage or mouth set");
        var geographicOwners = new HashMap<Coordinate,GlobalHydrologyAddressV2.Sample>();
        for (var sample : downstream.keySet()) {
            var coordinate = coordinate(sample);
            if (geographicOwners.putIfAbsent(coordinate, sample) != null)
                throw new IllegalArgumentException("Duplicate physical drainage vertex");
        }
        for (var mouth : verifiedGlobalMouths) {
            coordinate(mouth);
            if (!downstream.containsKey(mouth) || downstream.get(mouth) != null)
                throw new IllegalArgumentException("Unverified or nonterminal global mouth");
        }
        var catchments = new TreeMap<GlobalHydrologyAddressV2.Sample,CatchmentId>(ADDRESS_ORDER);
        var reaches = new TreeMap<GlobalHydrologyAddressV2.Sample,ReachId>(ADDRESS_ORDER);
        var sorted = new TreeSet<GlobalHydrologyAddressV2.Sample>(ADDRESS_ORDER);
        sorted.addAll(downstream.keySet());
        for (var start : sorted) {
            if (catchments.containsKey(start)) continue;
            var trail = new ArrayList<GlobalHydrologyAddressV2.Sample>();
            var active = new HashSet<GlobalHydrologyAddressV2.Sample>();
            var current = start;
            CatchmentId identity;
            while (true) {
                if (catchments.containsKey(current)) {
                    identity = catchments.get(current);
                    break;
                }
                if (!downstream.containsKey(current))
                    throw new IllegalStateException("Unknown or unloaded downstream vertex");
                if (!active.add(current))
                    throw new IllegalStateException("Cyclic drainage forest");
                trail.add(current);
                var next = downstream.get(current);
                if (next == null) {
                    if (!verifiedGlobalMouths.contains(current))
                        throw new IllegalStateException("Unverified global drainage termination");
                    identity = new CatchmentId(coordinate(current));
                    break;
                }
                if (!downstream.containsKey(next))
                    throw new IllegalStateException("Missing unloaded downstream vertex");
                reaches.put(current, new ReachId(coordinate(current), coordinate(next)));
                current = next;
            }
            for (var sample : trail) catchments.put(sample, identity);
        }
        return new Result(catchments, reaches);
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long rem = a % b;
            a = b;
            b = rem;
        }
        return a;
    }
}
