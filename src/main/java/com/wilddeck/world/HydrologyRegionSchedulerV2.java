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
