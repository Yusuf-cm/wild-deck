package com.wilddeck.world;

import java.util.*;

/**
 * Window-independent, strictly downhill D8 routing at one global sampling
 * resolution. Every neighboring elevation is obtained from the SAME
 * deterministic global terrain sampler, including unloaded neighbors.
 *
 * This is a local routing contract, not depression filling: pits/flats remain
 * unresolved, and neither longitude wrap nor polar adjacency is inferred.
 * The caller must provide globally unique source water (no shared-vertex
 * duplication) and explicitly verified outlets. An unloaded downhill
 * destination is retained as a known frontier edge.
 */
public final class HydrologyGlobalSlopeRouterV2 {
    private HydrologyGlobalSlopeRouterV2(){}

    @FunctionalInterface
    public interface Elevations {
        double at(GlobalHydrologyAddressV2.Sample address);
    }

    public record Source(GlobalHydrologyAddressV2.Sample address,double localWater) {
        public Source {
            Objects.requireNonNull(address);
            if(!Double.isFinite(localWater)||localWater<0)
                throw new IllegalArgumentException("Invalid local water");
        }
    }

    public static List<HydrologyFlowGraphV2.Node> route(
            Collection<Source> sources,Elevations elevations,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(elevations);
        Objects.requireNonNull(verifiedOutlets);
        var unique=new TreeMap<GlobalHydrologyAddressV2.Sample,Source>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var source:sources) {
            Objects.requireNonNull(source);
            if(unique.putIfAbsent(source.address(),source)!=null)
                throw new IllegalArgumentException("Duplicate global rainfall vertex");
        }
        if(unique.isEmpty())throw new IllegalArgumentException("Empty source window");
        var first=unique.firstKey();
        for(var source:unique.values()) {
            var address=source.address();
            if(address.level()!=first.level()||address.divisions()!=first.divisions())
                throw new IllegalArgumentException("Mixed global sampling resolutions");
        }
        if(!unique.keySet().containsAll(verifiedOutlets))
            throw new IllegalArgumentException("Verified outlet is not loaded");
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>();
        long maxX=(2L<<first.level())*first.divisions();
        long maxY=(1L<<first.level())*first.divisions();
        for(var source:unique.values()) {
            var address=source.address();
            if(verifiedOutlets.contains(address)) {
                nodes.add(new HydrologyFlowGraphV2.Node(
                    address,source.localWater(),null,true));
                continue;
            }
            double height=checked(elevations.at(address));
            double bestSlope=0;
            GlobalHydrologyAddressV2.Sample best=null;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                if(dx==0&&dy==0)continue;
                long x=address.x()+dx,y=address.y()+dy;
                if(x<0||x>maxX||y<0||y>maxY)continue;
                var neighbor=new GlobalHydrologyAddressV2.Sample(
                    first.level(),first.divisions(),x,y);
                double drop=height-checked(elevations.at(neighbor));
                if(!Double.isFinite(drop))
                    throw new IllegalArgumentException("Global elevation difference overflow");
                double slope=drop/(dx!=0&&dy!=0?StrictMath.sqrt(2):1);
                if(slope>bestSlope||
                   (slope==bestSlope&&slope>0&&
                    (best==null||HydrologyFlowGraphV2.ADDRESS_ORDER.compare(neighbor,best)<0))) {
                    bestSlope=slope;
                    best=neighbor;
                }
            }
            nodes.add(new HydrologyFlowGraphV2.Node(
                address,source.localWater(),best,false));
        }
        return List.copyOf(nodes);
    }

    /**
     * Canonical seeded-terrain elevation lookup for global sample addresses.
     * Shared region vertices resolve through one canonical region coordinate,
     * regardless of the region currently loaded. This does NOT connect the
     * duplicated +/-180-degree seam or the singular pole samples.
     */
    public static Elevations planetElevations(PlanetSurfaceV2 terrain) {
        Objects.requireNonNull(terrain);
        return address->{
            Objects.requireNonNull(address);
            long columns=2L<<address.level(),rows=1L<<address.level();
            int divisions=address.divisions();
            long column=Math.min(address.x()/divisions,columns-1);
            long row=Math.min(address.y()/divisions,rows-1);
            int x=Math.toIntExact(address.x()-column*divisions);
            int y=Math.toIntExact(address.y()-row*divisions);
            var region=new PlanetRegionGrid.Region(address.level(),column,row);
            return terrain.sample(PlanetRegionGrid.point(region,x,y,divisions))
                .elevationMeters();
        };
    }

    /**
     * Emit globally unique rainfall vertices for one INTERIOR region. Shared
     * west/north vertices are owned by the preceding region, matching the
     * existing rainfall-ownership contract. Planet-edge tiles are rejected
     * until seam and polar geometry has an authoritative ownership model.
     * Climate moisture is a dimensionless proxy: the caller supplies its
     * conversion to per-vertex water volume; this is not calibrated rainfall.
     */
    public static List<Source> seededOwnedInteriorRegion(
            PlanetClimateV2 climate,PlanetRegionGrid.Region region,
            int divisions,double volumePerMoisture) {
        Objects.requireNonNull(climate);
        Objects.requireNonNull(region);
        if(divisions<1||divisions>128||!Double.isFinite(volumePerMoisture)||
           volumePerMoisture<0)
            throw new IllegalArgumentException("Invalid seeded source parameters");
        long columns=2L<<region.level(),rows=1L<<region.level();
        if(region.column()==0||region.column()==columns-1||
           region.row()==0||region.row()==rows-1)
            throw new IllegalArgumentException("Seam and polar ownership unresolved");
        var sources=new ArrayList<Source>(divisions*divisions);
        for(int y=1;y<=divisions;y++)for(int x=1;x<=divisions;x++){
            var address=new GlobalHydrologyAddressV2.Sample(
                region.level(),divisions,
                region.column()*divisions+x,region.row()*divisions+y);
            double water=climate.sample(PlanetRegionGrid.point(region,x,y,divisions))
                .moisture()*volumePerMoisture;
            sources.add(new Source(address,water));
        }
        return List.copyOf(sources);
    }

    private static double checked(double elevation) {
        if(!Double.isFinite(elevation))
            throw new IllegalArgumentException("Non-finite global elevation");
        return elevation;
    }
}
