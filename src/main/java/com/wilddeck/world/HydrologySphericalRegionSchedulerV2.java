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
        var all=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        all.addAll(focus);
        all.addAll(HydrologyRegionSchedulerV2.pendingRegions(pending));
        return load(all,level,radius,maximumRegions,provider);
    }
}
