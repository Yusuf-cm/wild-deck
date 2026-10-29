package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic conservative discharge routing on globally addressed samples.
 *
 * A node represents one unique rainfall/storage source, not one copy per tile.
 * A downstream address may be absent from the loaded graph: its discharge is
 * exported as a pending frontier flux, never silently discarded. Importing
 * that address on a later solve completes the route without changing IDs.
 *
 * This graph solves explicitly supplied routes. It does not infer paths
 * across unknown terrain, resolve depressions, or persist unloaded tiles.
 */
public final class HydrologyFlowGraphV2 {
    private HydrologyFlowGraphV2() {}

    public static final Comparator<GlobalHydrologyAddressV2.Sample> ADDRESS_ORDER =
        Comparator.comparingInt(GlobalHydrologyAddressV2.Sample::level)
            .thenComparingInt(GlobalHydrologyAddressV2.Sample::divisions)
            .thenComparingLong(GlobalHydrologyAddressV2.Sample::y)
            .thenComparingLong(GlobalHydrologyAddressV2.Sample::x);

    /**
     * Terminal means a *known* physical outlet. A missing downstream with
     * terminal=false is an unresolved frontier, not a confirmed river mouth.
     */
    public record Node(GlobalHydrologyAddressV2.Sample address,
                       double localWater,
                       GlobalHydrologyAddressV2.Sample downstream,
                       boolean terminal) {
        public Node {
            Objects.requireNonNull(address, "address");
            if (!Double.isFinite(localWater) || localWater < 0)
                throw new IllegalArgumentException("Invalid local water");
            if (terminal && downstream != null)
                throw new IllegalArgumentException("Terminal cannot have downstream");
            if (downstream != null && (downstream.level() != address.level()
                    || downstream.divisions() != address.divisions()
                    || downstream.equals(address)
                    || Math.abs(downstream.x()-address.x())>1
                    || Math.abs(downstream.y()-address.y())>1))
                throw new IllegalArgumentException("Invalid downstream sample");
        }
    }

    public record Result(
            Map<GlobalHydrologyAddressV2.Sample, Double> discharge,
            Map<GlobalHydrologyAddressV2.Sample, Double> terminalOutflow,
            Map<GlobalHydrologyAddressV2.Sample, Double> pendingOutflow,
            Map<GlobalHydrologyAddressV2.Sample, GlobalHydrologyAddressV2.Sample> resolvedCatchments) {
        public Result {
            discharge = immutableSorted(discharge);
            terminalOutflow = immutableSorted(terminalOutflow);
            pendingOutflow = immutableSorted(pendingOutflow);
            resolvedCatchments = immutableSorted(resolvedCatchments);
        }
    }

    private static <T> Map<GlobalHydrologyAddressV2.Sample,T> immutableSorted(
            Map<GlobalHydrologyAddressV2.Sample,T> map) {
        var copy = new TreeMap<GlobalHydrologyAddressV2.Sample,T>(ADDRESS_ORDER);
        copy.putAll(map);
        return Collections.unmodifiableMap(copy);
    }

    private static void add(Map<GlobalHydrologyAddressV2.Sample,Double> map,
                            GlobalHydrologyAddressV2.Sample address, double value) {
        double sum = map.getOrDefault(address,0.0) + value;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Discharge overflow");
        map.put(address,sum);
    }

    public static Result solve(Collection<Node> sources) {
        Objects.requireNonNull(sources, "sources");
        if (sources.isEmpty()) throw new IllegalArgumentException("Empty flow graph");
        var nodes = new TreeMap<GlobalHydrologyAddressV2.Sample,Node>(ADDRESS_ORDER);
        for (Node node : sources) {
            Objects.requireNonNull(node, "node");
            if (!nodes.isEmpty()) {
                var first=nodes.firstKey();
                if (node.address().level()!=first.level() ||
                    node.address().divisions()!=first.divisions())
                    throw new IllegalArgumentException("Mixed sampling resolutions");
            }
            if (nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException("Duplicate global sample: "+node.address());
        }

        var indegree=new HashMap<GlobalHydrologyAddressV2.Sample,Integer>();
        var flow=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(ADDRESS_ORDER);
        for (Node node : nodes.values()) {
            indegree.put(node.address(),0);
            flow.put(node.address(),node.localWater());
        }
        for (Node node : nodes.values())
            if (node.downstream()!=null && nodes.containsKey(node.downstream()))
                indegree.merge(node.downstream(),1,Integer::sum);

        var ready=new PriorityQueue<GlobalHydrologyAddressV2.Sample>(ADDRESS_ORDER);
        for (var entry:indegree.entrySet())
            if (entry.getValue()==0) ready.add(entry.getKey());

        var pending=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(ADDRESS_ORDER);
        var terminals=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(ADDRESS_ORDER);
        var traversal=new ArrayList<GlobalHydrologyAddressV2.Sample>();
        while(!ready.isEmpty()) {
            var address=ready.remove();
            traversal.add(address);
            var node=nodes.get(address);
            double water=flow.get(address);
            if (node.terminal()) add(terminals,address,water);
            else if (node.downstream()==null) add(pending,address,water);
            else if (!nodes.containsKey(node.downstream()))
                add(pending,node.downstream(),water);
            else {
                add(flow,node.downstream(),water);
                int remaining=indegree.merge(node.downstream(),-1,Integer::sum);
                if (remaining==0) ready.add(node.downstream());
            }
        }
        if (traversal.size()!=nodes.size())
            throw new IllegalArgumentException("Flow graph contains a directed cycle");

        var resolved=new TreeMap<GlobalHydrologyAddressV2.Sample,
                                 GlobalHydrologyAddressV2.Sample>(ADDRESS_ORDER);
        for (int i=traversal.size()-1;i>=0;i--) {
            var address=traversal.get(i);
            var node=nodes.get(address);
            if (node.terminal()) resolved.put(address,address);
            else if (node.downstream()!=null && resolved.containsKey(node.downstream()))
                resolved.put(address,resolved.get(node.downstream()));
        }
        return new Result(flow,terminals,pending,resolved);
    }
}
