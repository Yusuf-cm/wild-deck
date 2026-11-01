package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic region selection for bounded hydrology simulation.
 * The provider resolves region identifiers into prepared graph nodes;
 * actual terrain loading, cache eviction and unloaded-frontier dispatch
 * are responsibilities of the runtime integrating this scheduler.
 */
public final class HydrologyRegionSchedulerV2 {
    private HydrologyRegionSchedulerV2(){}

    public record Region(long column,long row) implements Comparable<Region> {
        @Override public int compareTo(Region other) {
            int c=Long.compare(row,other.row);
            return c!=0?c:Long.compare(column,other.column);
        }
    }

    @FunctionalInterface
    public interface Provider {
        Collection<HydrologyFlowGraphV2.Node> load(Region region);
    }

    /**
     * Deterministic load hints for outstanding cross-region receipts.
     * At shared vertices, west/north owns the sample, matching the rainfall
     * ownership convention. A hint is not a verified downstream route.
     * Mixed sampling resolutions cannot share this unqualified Region type.
     */
    public static List<Region> pendingRegions(HydrologyEpochInboxV2.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var selected=new TreeSet<Region>();
        GlobalHydrologyAddressV2.Sample resolution=null;
        for(var key:snapshot.receipts().keySet()){
            if(snapshot.delivered().contains(key))continue;
            var destination=key.to();
            if(resolution==null)resolution=destination;
            else if(resolution.level()!=destination.level()||
                    resolution.divisions()!=destination.divisions())
                throw new IllegalArgumentException("Mixed pending receipt resolutions");
            long column=destination.x()==0?0:(destination.x()-1)/destination.divisions();
            long row=destination.y()==0?0:(destination.y()-1)/destination.divisions();
            selected.add(new Region(column,row));
        }
        return List.copyOf(selected);
    }

    /**
     * Include regions containing parked incoming water in the same bounded,
     * deterministic load plan as player/explicit focus regions. Pending
     * destinations receive the same halo radius so their downstream neighbors
     * can be considered, but this does not discover an unknown river route.
     * A failed budget check happens before any provider is invoked.
     */
    public static List<Collection<HydrologyFlowGraphV2.Node>> loadWithPending(
            Collection<Region> focus,HydrologyEpochInboxV2.Snapshot pending,
            int radius,int maximumRegions,Provider provider) {
        Objects.requireNonNull(focus);
        Objects.requireNonNull(pending);
        var selected=new TreeSet<Region>();
        for(var region:focus)selected.add(Objects.requireNonNull(region));
        selected.addAll(pendingRegions(pending));
        return load(selected,radius,maximumRegions,provider);
    }

    /**
     * Selects a square Chebyshev-radius neighborhood around each focus,
     * deduplicates overlaps and loads regions in stable row-major order.
     * The cap protects callers from accidental planet-scale materialization.
     */
    public static List<Collection<HydrologyFlowGraphV2.Node>> load(
            Collection<Region> focus,int radius,int maximumRegions,Provider provider) {
        Objects.requireNonNull(focus);
        Objects.requireNonNull(provider);
        if(radius<0||maximumRegions<1)
            throw new IllegalArgumentException("Invalid region scheduling limits");
        var selected=new TreeSet<Region>();
        // A single complete neighborhood already exceeds the budget.
        // Check before any O(radius^2) loop or provider call.
        long side=2L*radius+1;
        if(!focus.isEmpty()&&(side>maximumRegions||side>maximumRegions/side))
            throw new IllegalArgumentException("Region budget exceeded");
        for(var center:focus){
            Objects.requireNonNull(center);
            for(long dy=-(long)radius;dy<=radius;dy++){
                for(long dx=-(long)radius;dx<=radius;dx++){
                    long x=Math.addExact(center.column(),dx);
                    long y=Math.addExact(center.row(),dy);
                    selected.add(new Region(x,y));
                    if(selected.size()>maximumRegions)
                        throw new IllegalArgumentException("Region budget exceeded");
                }
            }
        }
        var loaded=new ArrayList<Collection<HydrologyFlowGraphV2.Node>>();
        for(var region:selected)
            loaded.add(List.copyOf(Objects.requireNonNull(provider.load(region),
                "Provider returned null region")));
        return List.copyOf(loaded);
    }
}
