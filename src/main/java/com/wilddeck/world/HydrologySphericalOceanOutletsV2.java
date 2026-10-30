package com.wilddeck.world;

import java.util.*;

/**
 * Classifies ocean-connected submerged cells on a complete canonical sphere.
 * Elevation below sea level alone is NOT proof of an ocean: a caller must
 * provide at least one independently verified submerged seed for each ocean
 * component. Unseeded disconnected depressions remain inland water.
 *
 * This bounded, single-resolution mask is a static shoreline snapshot, not
 * oceanography, tides, automatic geographic seed discovery or a streamed
 * partial-planet solver. Changing terrain or sea level requires rebuilding it.
 */
public final class HydrologySphericalOceanOutletsV2 {
    private HydrologySphericalOceanOutletsV2() {}

    public record Classification(
            Set<GlobalHydrologyAddressV2.Sample> ocean,
            Set<GlobalHydrologyAddressV2.Sample> inland,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> oceanComponents) {
        public Classification {
            Objects.requireNonNull(ocean);
            Objects.requireNonNull(inland);
            Objects.requireNonNull(oceanComponents);
            var order=HydrologyFlowGraphV2.ADDRESS_ORDER;
            var wetOcean=new TreeSet<GlobalHydrologyAddressV2.Sample>(order);
            wetOcean.addAll(ocean);
            var wetInland=new TreeSet<GlobalHydrologyAddressV2.Sample>(order);
            wetInland.addAll(inland);
            var components=new TreeMap<GlobalHydrologyAddressV2.Sample,
                                       GlobalHydrologyAddressV2.Sample>(order);
            components.putAll(oceanComponents);
            if(!Collections.disjoint(wetOcean,wetInland) ||
               !components.keySet().equals(wetOcean))
                throw new IllegalArgumentException("Invalid ocean classification");
            ocean=Collections.unmodifiableSet(wetOcean);
            inland=Collections.unmodifiableSet(wetInland);
            oceanComponents=Collections.unmodifiableMap(components);
        }
    }

    public static Classification classify(
            Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            double seaLevel,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOceanSeeds) {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(elevations);
        Objects.requireNonNull(verifiedOceanSeeds);
        if(!Double.isFinite(seaLevel) || verifiedOceanSeeds.isEmpty())
            throw new IllegalArgumentException("Verified ocean seeds and finite sea level required");

        var order=HydrologyFlowGraphV2.ADDRESS_ORDER;
        var heights=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(order);
        for(var source:sources) {
            Objects.requireNonNull(source);
            var address=source.address();
            if(!HydrologySphericalTopologyV2.isCanonical(address) ||
               heights.containsKey(address))
                throw new IllegalArgumentException("Duplicate or noncanonical ocean sample");
            double height=elevations.at(address);
            if(!Double.isFinite(height))
                throw new IllegalArgumentException("Nonfinite ocean terrain");
            heights.put(address,height);
            if(heights.size()>HydrologySphericalBasinRouterV2.MAX_SAMPLES)
                throw new IllegalArgumentException("Ocean mask sample budget exceeded");
        }
        if(heights.isEmpty())
            throw new IllegalArgumentException("Empty ocean surface");
        var first=heights.firstKey();
        for(var address:heights.keySet()) {
            if(address.level()!=first.level() ||
               address.divisions()!=first.divisions() ||
               !heights.keySet().containsAll(HydrologySphericalTopologyV2.neighbors(address)))
                throw new IllegalArgumentException("Ocean classification requires a closed single-resolution sphere");
        }
        for(var seed:verifiedOceanSeeds)
            if(seed==null || !heights.containsKey(seed) ||
               !(heights.get(seed)<seaLevel))
                throw new IllegalArgumentException("Unverified or unsubmerged ocean seed");

        var ocean=new TreeSet<GlobalHydrologyAddressV2.Sample>(order);
        var inland=new TreeSet<GlobalHydrologyAddressV2.Sample>(order);
        var components=new TreeMap<GlobalHydrologyAddressV2.Sample,
                                   GlobalHydrologyAddressV2.Sample>(order);
        var visited=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var start:heights.keySet()) {
            if(!(heights.get(start)<seaLevel) || !visited.add(start))
                continue;
            var group=new TreeSet<GlobalHydrologyAddressV2.Sample>(order);
            var queue=new ArrayDeque<GlobalHydrologyAddressV2.Sample>();
            queue.add(start);
            boolean verified=false;
            while(!queue.isEmpty()) {
                var current=queue.remove();
                group.add(current);
                if(verifiedOceanSeeds.contains(current))verified=true;
                for(var neighbor:HydrologySphericalTopologyV2.neighbors(current))
                    if(heights.get(neighbor)<seaLevel && visited.add(neighbor))
                        queue.add(neighbor);
            }
            if(verified) {
                ocean.addAll(group);
                var anchor=group.first();
                for(var cell:group)components.put(cell,anchor);
            } else inland.addAll(group);
        }
        return new Classification(ocean,inland,components);
    }

    /** Routes a verified complete sphere to the classified ocean cells. */
    public static List<HydrologyFlowGraphV2.Node> route(
            Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            double seaLevel,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOceanSeeds) {
        var mask=classify(sources,elevations,seaLevel,verifiedOceanSeeds);
        return HydrologySphericalBasinRouterV2.route(sources,elevations,mask.ocean());
    }

    /** Extracts potential lake storage only after verifying ocean connectivity. */
    public static HydrologySphericalLakeBasinsV2.Plan lakes(
            Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            double seaLevel,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOceanSeeds,
            double radiusMeters) {
        var mask=classify(sources,elevations,seaLevel,verifiedOceanSeeds);
        return HydrologySphericalLakeBasinsV2.extract(
            sources,elevations,mask.ocean(),radiusMeters);
    }
}
