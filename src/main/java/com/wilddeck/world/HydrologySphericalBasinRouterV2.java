package com.wilddeck.world;

import java.util.*;

/**
 * Bounded, opt-in, whole-surface priority-flood drainage with caller-verified
 * outlets. Requires a topologically CLOSED canonical spherical sample graph:
 * every neighbor of every supplied sample must also be supplied. A partial
 * streamed window fails closed rather than treating its edge as an ocean.
 *
 * A route over a depression is a POTENTIAL spill path at the computed fill
 * level, not a claim that an empty lake immediately discharges. The caller
 * must apply lake storage and its stage/volume budget before releasing water.
 * No physical ocean mask, mixed-LOD drainage or distributed solve is inferred.
 */
public final class HydrologySphericalBasinRouterV2 {
    private HydrologySphericalBasinRouterV2() {}
    public static final int MAX_SAMPLES=100_000;

    private record Path(double spill,int steps,
                        GlobalHydrologyAddressV2.Sample downstream) {}
    private record QueueItem(GlobalHydrologyAddressV2.Sample address,
                             double spill,int steps) {}

    public static List<HydrologyFlowGraphV2.Node> route(
            Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(elevations);
        Objects.requireNonNull(verifiedOutlets);
        var owned=new TreeMap<GlobalHydrologyAddressV2.Sample,
                              HydrologyGlobalSlopeRouterV2.Source>(
                                  HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var source:sources) {
            Objects.requireNonNull(source);
            if(!HydrologySphericalTopologyV2.isCanonical(source.address()))
                throw new IllegalArgumentException("Noncanonical spherical sample");
            if(owned.putIfAbsent(source.address(),source)!=null)
                throw new IllegalArgumentException("Duplicate global water source");
            if(owned.size()>MAX_SAMPLES)
                throw new IllegalArgumentException("Global drainage budget exceeded");
        }
        if(owned.isEmpty()||verifiedOutlets.isEmpty())
            throw new IllegalArgumentException("Whole-surface drainage requires verified outlets");
        var first=owned.firstKey();
        for(var sample:owned.keySet())
            if(sample.level()!=first.level()||sample.divisions()!=first.divisions())
                throw new IllegalArgumentException("Mixed spherical sampling resolutions");
        if(!owned.keySet().containsAll(verifiedOutlets))
            throw new IllegalArgumentException("Unloaded or noncanonical outlet");

        var heights=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var adjacent=new TreeMap<GlobalHydrologyAddressV2.Sample,
                                 List<GlobalHydrologyAddressV2.Sample>>(
                                     HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var address:owned.keySet()) {
            double height=elevations.at(address);
            if(!Double.isFinite(height))
                throw new IllegalArgumentException("Nonfinite terrain elevation");
            heights.put(address,height);
            var neighbors=HydrologySphericalTopologyV2.neighbors(address);
            if(!owned.keySet().containsAll(neighbors))
                throw new IllegalArgumentException(
                    "Unknown spherical neighbor: whole-surface drainage required");
            adjacent.put(address,neighbors);
        }

        var paths=new HashMap<GlobalHydrologyAddressV2.Sample,Path>();
        var queue=new PriorityQueue<QueueItem>(
            Comparator.comparingDouble(QueueItem::spill)
                .thenComparingInt(QueueItem::steps)
                .thenComparing(QueueItem::address,HydrologyFlowGraphV2.ADDRESS_ORDER));
        for(var outlet:verifiedOutlets) {
            var path=new Path(heights.get(outlet),0,null);
            paths.put(outlet,path);
            queue.add(new QueueItem(outlet,path.spill(),0));
        }
        while(!queue.isEmpty()) {
            var item=queue.remove();
            var current=paths.get(item.address());
            if(Double.compare(current.spill(),item.spill())!=0||
               current.steps()!=item.steps())continue;
            for(var neighbor:adjacent.get(item.address())) {
                if(verifiedOutlets.contains(neighbor))continue;
                double spill=Math.max(heights.get(neighbor),item.spill());
                int steps=Math.addExact(item.steps(),1);
                var previous=paths.get(neighbor);
                int cost=previous==null?-1:Double.compare(spill,previous.spill());
                boolean better=previous==null||cost<0||
                    (cost==0&&(steps<previous.steps()||
                    (steps==previous.steps()&&
                    HydrologyFlowGraphV2.ADDRESS_ORDER.compare(
                        item.address(),previous.downstream())<0)));
                if(better) {
                    paths.put(neighbor,new Path(spill,steps,item.address()));
                    if(previous==null||cost<0||steps<previous.steps())
                        queue.add(new QueueItem(neighbor,spill,steps));
                }
            }
        }
        if(paths.size()!=owned.size())
            throw new IllegalArgumentException("Unreachable spherical drainage samples");
        var result=new ArrayList<HydrologyFlowGraphV2.Node>(owned.size());
        for(var source:owned.values()) {
            boolean terminal=verifiedOutlets.contains(source.address());
            var downstream=terminal?null:paths.get(source.address()).downstream();
            result.add(new HydrologyFlowGraphV2.Node(
                source.address(),source.localWater(),downstream,terminal));
        }
        return List.copyOf(result);
    }
}
