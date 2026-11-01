package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in bounded region scheduler for canonical spherical hydrology.
 * Longitude neighborhoods wrap at the dateline. Latitude neighborhoods
 * clamp at the pole instead of requesting invalid negative/outside tiles.
 *
 * This is a region-loading policy, not a polar graph or depression solver:
 * a polar vertex can route into any longitude of the adjacent latitude ring,
 * even if that region is not loaded. The spherical provider samples such
 * neighbors globally and the frontier retains unloaded destinations.
 */
public final class HydrologySphericalRegionSchedulerV2 {
    private HydrologySphericalRegionSchedulerV2(){}

    public static List<Collection<HydrologyFlowGraphV2.Node>> load(
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            int level,int radius,int maximumRegions,
            HydrologyRegionSchedulerV2.Provider provider) {
        Objects.requireNonNull(focus);
        Objects.requireNonNull(provider);
        if(level<0||level>30||radius<0||maximumRegions<1)
            throw new IllegalArgumentException("Invalid spherical scheduling limits");
        long columns=2L<<level,rows=1L<<level;
        long side=2L*radius+1;
        long countX=Math.min(columns,side);
        var selected=new TreeSet<HydrologyRegionSchedulerV2.Region>();
        for(var center:focus){
            Objects.requireNonNull(center);
            new PlanetRegionGrid.Region(level,center.column(),center.row());
            long firstRow=Math.max(0,center.row()-radius);
            long lastRow=Math.min(rows-1,center.row()+radius);
            long countY=lastRow-firstRow+1;
            if(countX>maximumRegions/countY)
                throw new IllegalArgumentException("Spherical region budget exceeded");
            for(long y=firstRow;y<=lastRow;y++){
                for(long i=0;i<countX;i++){
                    long x=Math.floorMod(center.column()-radius+i,columns);
                    selected.add(new HydrologyRegionSchedulerV2.Region(x,y));
                    if(selected.size()>maximumRegions)
                        throw new IllegalArgumentException("Spherical region budget exceeded");
                }
            }
        }
        var result=new ArrayList<Collection<HydrologyFlowGraphV2.Node>>();
        for(var region:selected)
            result.add(List.copyOf(Objects.requireNonNull(provider.load(region),
                "Provider returned null region")));
        return List.copyOf(result);
    }

    public static List<Collection<HydrologyFlowGraphV2.Node>> loadWithPending(
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            HydrologyEpochInboxV2.Snapshot pending,
            int level,int radius,int maximumRegions,
            HydrologyRegionSchedulerV2.Provider provider) {
        Objects.requireNonNull(focus);
        Objects.requireNonNull(pending);
        // Region coordinates are not resolution-qualified. Never load a
        // different-level receipt into a superficially matching tile.
        // Reject noncanonical seam/pole aliases at BOTH ends of a receipt.
        // A noncanonical source can otherwise replay the same geographic
        // discharge under a second edge key and double-count incoming water.
        for (var key : pending.receipts().keySet()) {
            if (pending.delivered().contains(key)) continue;
            var destination = key.to();
            if (destination.level() != level ||
                !HydrologySphericalTopologyV2.isCanonical(destination) ||
                !HydrologySphericalTopologyV2.isCanonical(key.from()))
                throw new IllegalArgumentException(
                    "Pending spherical receipt has incompatible resolution or address");
        }
        var all=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        all.addAll(focus);
        all.addAll(HydrologyRegionSchedulerV2.pendingRegions(pending));
        return load(all,level,radius,maximumRegions,provider);
    }

    /**
     * Resolution-qualified spherical scheduling. The legacy level-only
     * overload cannot distinguish different divisions at the same level.
     * Refuse incompatible pending receipts BEFORE invoking any provider.
     * This is a strict single-resolution contract, not mixed-LOD routing.
     */
    public static List<Collection<HydrologyFlowGraphV2.Node>> loadWithPending(
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            HydrologyEpochInboxV2.Snapshot pending,
            int level,int divisions,int radius,int maximumRegions,
            HydrologyRegionSchedulerV2.Provider provider) {
        Objects.requireNonNull(pending);
        if(divisions<1||divisions>128)
            throw new IllegalArgumentException("Invalid spherical sample divisions");
        for(var key:pending.receipts().keySet()) {
            if(pending.delivered().contains(key))continue;
            if(key.to().divisions()!=divisions)
                throw new IllegalArgumentException(
                    "Pending spherical receipt has incompatible sample divisions");
        }
        return loadWithPending(focus,pending,level,radius,maximumRegions,provider);
    }
}
