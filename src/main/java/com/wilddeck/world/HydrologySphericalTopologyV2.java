package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in canonical topology for the equirectangular global hydrology grid.
 * The duplicated +/-180 longitude vertex is x=0; all longitude samples
 * at either geographic pole collapse to one x=0 vertex.
 *
 * At a pole every longitude on the adjacent latitude ring is a neighbor.
 * Enumerating that ring is bounded to avoid accidentally scanning a planet.
 * This is one-resolution D8-like surface routing, not global depression
 * filling, watershed reconciliation or geodesic climate calibration.
 */
public final class HydrologySphericalTopologyV2 {
    private HydrologySphericalTopologyV2(){}
    public static final int MAX_POLAR_RING=4096;

    public static GlobalHydrologyAddressV2.Sample canonical(
            GlobalHydrologyAddressV2.Sample sample) {
        Objects.requireNonNull(sample);
        long maxX=(2L<<sample.level())*sample.divisions();
        long maxY=(1L<<sample.level())*sample.divisions();
        if(sample.x()==maxX||sample.y()==0||sample.y()==maxY)
            return new GlobalHydrologyAddressV2.Sample(
                sample.level(),sample.divisions(),0,sample.y());
        return sample;
    }

    public static boolean isCanonical(GlobalHydrologyAddressV2.Sample sample) {
        Objects.requireNonNull(sample);
        return sample.equals(canonical(sample));
    }

    /** Accept legacy local edges, plus canonical seam and polar edges. */
    public static boolean adjacent(GlobalHydrologyAddressV2.Sample from,
                                   GlobalHydrologyAddressV2.Sample to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if(from.equals(to)||from.level()!=to.level()||
           from.divisions()!=to.divisions())return false;
        long dx=Math.abs(from.x()-to.x()),dy=Math.abs(from.y()-to.y());
        if(dx<=1&&dy<=1)return true;
        if(!isCanonical(from)||!isCanonical(to))return false;
        long maxX=(2L<<from.level())*from.divisions();
        long maxY=(1L<<from.level())*from.divisions();
        if(dy==1&&(from.y()==0||from.y()==maxY||
                    to.y()==0||to.y()==maxY))return true;
        return dy<=1&&((from.x()==0&&to.x()==maxX-1)||
                       (to.x()==0&&from.x()==maxX-1));
    }

    public static List<GlobalHydrologyAddressV2.Sample> neighbors(
            GlobalHydrologyAddressV2.Sample source) {
        Objects.requireNonNull(source);
        if(!isCanonical(source))
            throw new IllegalArgumentException("Noncanonical spherical source");
        long maxX=(2L<<source.level())*source.divisions();
        long maxY=(1L<<source.level())*source.divisions();
        var adjacent=new TreeSet<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        if(source.y()==0||source.y()==maxY) {
            if(maxX>MAX_POLAR_RING)
                throw new IllegalArgumentException("Polar neighbor scan budget exceeded");
            long ring=source.y()==0?1:maxY-1;
            for(long x=0;x<maxX;x++)
                adjacent.add(canonical(new GlobalHydrologyAddressV2.Sample(
                    source.level(),source.divisions(),x,ring)));
        } else {
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                if(dx==0&&dy==0)continue;
                long y=source.y()+dy;
                if(y<0||y>maxY)continue;
                long x=Math.floorMod(source.x()+dx,maxX);
                adjacent.add(canonical(new GlobalHydrologyAddressV2.Sample(
                    source.level(),source.divisions(),x,y)));
            }
        }
        adjacent.remove(source);
        return List.copyOf(adjacent);
    }

    /** Great-circle angular separation in radians, positive for distinct sites. */
    public static double angularDistance(GlobalHydrologyAddressV2.Sample a,
                                         GlobalHydrologyAddressV2.Sample b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        if(!isCanonical(a)||!isCanonical(b)||a.equals(b)||
           a.level()!=b.level()||a.divisions()!=b.divisions())
            throw new IllegalArgumentException("Invalid spherical distance");
        long maxX=(2L<<a.level())*a.divisions();
        long maxY=(1L<<a.level())*a.divisions();
        double latA=StrictMath.PI/2-StrictMath.PI*a.y()/maxY;
        double latB=StrictMath.PI/2-StrictMath.PI*b.y()/maxY;
        double dLat=latA-latB;
        double dLon=2*StrictMath.PI*(a.x()-b.x())/maxX;
        double sinLat=StrictMath.sin(dLat/2),sinLon=StrictMath.sin(dLon/2);
        double h=sinLat*sinLat+
            StrictMath.cos(latA)*StrictMath.cos(latB)*sinLon*sinLon;
        h=Math.max(0,Math.min(1,h));
        double distance=2*StrictMath.atan2(StrictMath.sqrt(h),StrictMath.sqrt(1-h));
        if(!(distance>0)||!Double.isFinite(distance))
            throw new IllegalArgumentException("Degenerate spherical distance");
        return distance;
    }

    /**
     * Deterministic steepest angular descent, including unloaded neighbors.
     * Closed depressions and flats remain unresolved, never fake ocean mouths.
     */
    public static List<HydrologyFlowGraphV2.Node> route(
            Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(elevations);
        Objects.requireNonNull(verifiedOutlets);
        var unique=new TreeMap<GlobalHydrologyAddressV2.Sample,
                               HydrologyGlobalSlopeRouterV2.Source>(
                                   HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var source:sources) {
            Objects.requireNonNull(source);
            if(!isCanonical(source.address()))
                throw new IllegalArgumentException("Noncanonical rainfall vertex");
            if(unique.putIfAbsent(source.address(),source)!=null)
                throw new IllegalArgumentException("Duplicate rainfall vertex");
        }
        if(unique.isEmpty())throw new IllegalArgumentException("Empty spherical graph");
        var first=unique.firstKey();
        for(var address:unique.keySet())
            if(address.level()!=first.level()||address.divisions()!=first.divisions())
                throw new IllegalArgumentException("Mixed sampling resolutions");
        if(!unique.keySet().containsAll(verifiedOutlets))
            throw new IllegalArgumentException("Unloaded or noncanonical verified outlet");
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>();
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
            for(var neighbor:neighbors(address)) {
                double drop=height-checked(elevations.at(neighbor));
                if(!Double.isFinite(drop))
                    throw new IllegalArgumentException("Elevation difference overflow");
                double slope=drop/angularDistance(address,neighbor);
                if(!Double.isFinite(slope))
                    throw new IllegalArgumentException("Spherical slope overflow");
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

    private static double checked(double elevation) {
        if(!Double.isFinite(elevation))
            throw new IllegalArgumentException("Nonfinite spherical elevation");
        return elevation;
    }
}
