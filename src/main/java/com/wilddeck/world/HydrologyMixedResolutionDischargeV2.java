package com.wilddeck.world;

import java.math.BigInteger;
import java.util.*;

/**
 * Conservative, deterministic routing over an explicitly verified mixed-LOD drainage forest.
 * Each geographic vertex has one water owner, regardless of sampling resolution.
 * Downstream aliases are references only; they never introduce rainfall twice.
 *
 * This is a finite graph solver, not a terrain oracle, distributed receipt ledger,
 * lake solver, or proof that a caller's terminal mouths are actually oceans.
 */
public final class HydrologyMixedResolutionDischargeV2 {
    private HydrologyMixedResolutionDischargeV2() {}

    private static final Comparator<GlobalHydrologyAddressV2.Sample> ORDER =
        Comparator.comparing(HydrologyGlobalCatchmentsV2::coordinate,
            Comparator.comparing(HydrologyGlobalCatchmentsV2.Coordinate::stableKey))
            .thenComparingInt(GlobalHydrologyAddressV2.Sample::level)
            .thenComparingInt(GlobalHydrologyAddressV2.Sample::divisions);
    private static final Comparator<HydrologyGlobalCatchmentsV2.Coordinate> COORD_ORDER =
        Comparator.comparing(HydrologyGlobalCatchmentsV2.Coordinate::stableKey);

    public record Node(GlobalHydrologyAddressV2.Sample address, double localWater,
                       GlobalHydrologyAddressV2.Sample downstream, boolean terminal) {
        public Node {
            Objects.requireNonNull(address);
            HydrologyGlobalCatchmentsV2.coordinate(address);
            if (!Double.isFinite(localWater) || localWater < 0)
                throw new IllegalArgumentException("Invalid local water");
            if (terminal && downstream != null)
                throw new IllegalArgumentException("Terminal has downstream");
            if (downstream != null) {
                HydrologyGlobalCatchmentsV2.coordinate(downstream);
                if (!neighbor(address, downstream))
                    throw new IllegalArgumentException("Nonadjacent mixed-resolution drainage edge");
            }
        }
    }

    public record Result(Map<GlobalHydrologyAddressV2.Sample, Double> discharge,
                         Map<GlobalHydrologyAddressV2.Sample, Double> terminalOutflow,
                         Map<HydrologyGlobalCatchmentsV2.Coordinate, Double> pendingOutflow,
                         Map<GlobalHydrologyAddressV2.Sample,
                             HydrologyGlobalCatchmentsV2.CatchmentId> resolvedCatchments) {
        public Result {
            var flows = new TreeMap<GlobalHydrologyAddressV2.Sample, Double>(ORDER);
            flows.putAll(discharge);
            discharge = Collections.unmodifiableMap(flows);
            var terminals = new TreeMap<GlobalHydrologyAddressV2.Sample, Double>(ORDER);
            terminals.putAll(terminalOutflow);
            terminalOutflow = Collections.unmodifiableMap(terminals);
            var frontiers = new TreeMap<HydrologyGlobalCatchmentsV2.Coordinate, Double>(COORD_ORDER);
            frontiers.putAll(pendingOutflow);
            pendingOutflow = Collections.unmodifiableMap(frontiers);
            var identities = new TreeMap<GlobalHydrologyAddressV2.Sample,
                HydrologyGlobalCatchmentsV2.CatchmentId>(ORDER);
            identities.putAll(resolvedCatchments);
            resolvedCatchments = Collections.unmodifiableMap(identities);
        }
    }

    /**
     * A verified mouth must be an owned terminal. Unresolved edges are exported
     * by physical coordinate so aliases at different LODs converge in one inbox.
     * An unresolved local sink exports at its own coordinate without inventing a mouth.
     */
    public static Result solve(Collection<Node> sources,
                               Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        return solveWithIncoming(sources, verifiedMouths, Map.of());
    }

    /**
     * Resume conserved water from a previously exported physical frontier.
     * Each imported coordinate must be owned by exactly one loaded vertex.
     * The caller MUST atomically consume imported receipts exactly once; this
     * pure solver cannot authenticate, acknowledge, or persist delivery.
     */
    public static Result solveWithIncoming(Collection<Node> sources,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths,
            Map<HydrologyGlobalCatchmentsV2.Coordinate, Double> incoming) {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(verifiedMouths);
        Objects.requireNonNull(incoming);
        if (sources.isEmpty()) throw new IllegalArgumentException("Empty drainage graph");
        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
                                 GlobalHydrologyAddressV2.Sample>();
        var nodes = new TreeMap<GlobalHydrologyAddressV2.Sample, Node>(ORDER);
        for (var node : sources) {
            Objects.requireNonNull(node);
            var coordinate = HydrologyGlobalCatchmentsV2.coordinate(node.address());
            if (owners.putIfAbsent(coordinate, node.address()) != null)
                throw new IllegalArgumentException("Duplicate geographic water ownership");
            nodes.put(node.address(), node);
        }
        for (var mouth : verifiedMouths) {
            Objects.requireNonNull(mouth);
            HydrologyGlobalCatchmentsV2.coordinate(mouth);
            if (!nodes.containsKey(mouth) || !nodes.get(mouth).terminal())
                throw new IllegalArgumentException("Mouth must be an owned terminal");
        }
        var next = new HashMap<GlobalHydrologyAddressV2.Sample,
                               GlobalHydrologyAddressV2.Sample>();
        var indegree = new HashMap<GlobalHydrologyAddressV2.Sample, Integer>();
        var flow = new TreeMap<GlobalHydrologyAddressV2.Sample, Double>(ORDER);
        for (var node : nodes.values()) {
            if (node.terminal() != verifiedMouths.contains(node.address()))
                throw new IllegalArgumentException("Unverified terminal or missing mouth");
            indegree.put(node.address(), 0);
            flow.put(node.address(), node.localWater());
        }
        for (var receipt : incoming.entrySet()) {
            Objects.requireNonNull(receipt.getKey());
            var amount = Objects.requireNonNull(receipt.getValue());
            if (!Double.isFinite(amount) || amount < 0)
                throw new IllegalArgumentException("Invalid imported water");
            var destination = owners.get(receipt.getKey());
            if (destination == null)
                throw new IllegalArgumentException("Unloaded receipt destination");
            add(flow, destination, amount);
        }
        for (var node : nodes.values()) {
            if (node.downstream() == null) continue;
            var destination = owners.get(HydrologyGlobalCatchmentsV2.coordinate(node.downstream()));
            if (destination != null) {
                next.put(node.address(), destination);
                indegree.merge(destination, 1, Integer::sum);
            }
        }
        var ready = new PriorityQueue<GlobalHydrologyAddressV2.Sample>(ORDER);
        for (var entry : indegree.entrySet())
            if (entry.getValue() == 0) ready.add(entry.getKey());
        var terminal = new TreeMap<GlobalHydrologyAddressV2.Sample, Double>(ORDER);
        var pending = new TreeMap<HydrologyGlobalCatchmentsV2.Coordinate, Double>(COORD_ORDER);
        var visited = new ArrayList<GlobalHydrologyAddressV2.Sample>();
        while (!ready.isEmpty()) {
            var address = ready.remove();
            visited.add(address);
            var node = nodes.get(address);
            var amount = flow.get(address);
            if (node.terminal()) terminal.put(address, amount);
            else if (node.downstream() == null)
                add(pending, HydrologyGlobalCatchmentsV2.coordinate(address), amount);
            else if (!next.containsKey(address))
                add(pending, HydrologyGlobalCatchmentsV2.coordinate(node.downstream()), amount);
            else {
                var destination = next.get(address);
                add(flow, destination, amount);
                if (indegree.merge(destination, -1, Integer::sum) == 0) ready.add(destination);
            }
        }
        if (visited.size() != nodes.size())
            throw new IllegalArgumentException("Cyclic mixed-resolution drainage");
        var resolved = new TreeMap<GlobalHydrologyAddressV2.Sample,
                                   HydrologyGlobalCatchmentsV2.CatchmentId>(ORDER);
        for (int i = visited.size() - 1; i >= 0; i--) {
            var address = visited.get(i);
            if (nodes.get(address).terminal())
                resolved.put(address, new HydrologyGlobalCatchmentsV2.CatchmentId(
                    HydrologyGlobalCatchmentsV2.coordinate(address)));
            else if (next.containsKey(address) && resolved.containsKey(next.get(address)))
                resolved.put(address, resolved.get(next.get(address)));
        }
        return new Result(flow, terminal, pending, resolved);
    }

    private static <K> void add(Map<K, Double> totals, K key, double amount) {
        double sum = totals.getOrDefault(key, 0.0) + amount;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Discharge overflow");
        totals.put(key, sum);
    }

    /** Require D8 adjacency when projected exactly onto either endpoint's grid. */
    private static boolean neighbor(GlobalHydrologyAddressV2.Sample from,
                                    GlobalHydrologyAddressV2.Sample to) {
        var a = HydrologyGlobalCatchmentsV2.coordinate(from);
        var b = HydrologyGlobalCatchmentsV2.coordinate(to);
        if (a.equals(b)) return false;
        var projectedTo = project(b, from.level(), from.divisions());
        if (projectedTo != null && adjacent(from, projectedTo)) return true;
        var projectedFrom = project(a, to.level(), to.divisions());
        return projectedFrom != null && adjacent(projectedFrom, to);
    }

    private static GlobalHydrologyAddressV2.Sample project(
            HydrologyGlobalCatchmentsV2.Coordinate coordinate, int level, int divisions) {
        long width = (2L << level) * divisions;
        long height = (1L << level) * divisions;
        var x = BigInteger.valueOf(coordinate.lonNumerator()).multiply(BigInteger.valueOf(width));
        var y = BigInteger.valueOf(coordinate.latNumerator()).multiply(BigInteger.valueOf(height));
        var dx = x.divideAndRemainder(BigInteger.valueOf(coordinate.lonDenominator()));
        var dy = y.divideAndRemainder(BigInteger.valueOf(coordinate.latDenominator()));
        if (dx[1].signum() != 0 || dy[1].signum() != 0) return null;
        return new GlobalHydrologyAddressV2.Sample(level, divisions,
            dx[0].longValueExact(), dy[0].longValueExact());
    }

    private static boolean adjacent(GlobalHydrologyAddressV2.Sample a,
                                    GlobalHydrologyAddressV2.Sample b) {
        if (a.equals(b)) return false;
        long width = (2L << a.level()) * a.divisions();
        long height = (1L << a.level()) * a.divisions();
        long dy = Math.abs(a.y() - b.y());
        if (dy > 1) return false;
        if ((a.y() == 0 || a.y() == height || b.y() == 0 || b.y() == height))
            return dy == 1;
        long dx = Math.abs(a.x() - b.x());
        return Math.min(dx, width - dx) <= 1;
    }
}
