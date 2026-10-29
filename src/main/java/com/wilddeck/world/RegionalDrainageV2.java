package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic regional D8 downhill routing and rainfall accumulation.
 * This is a local hydrology prototype: edge outlets and internal sinks are
 * explicit; it does not yet solve global rivers or lake spill elevations.
 */
public final class RegionalDrainageV2 {
    private RegionalDrainageV2(){}
    public record Drainage(int width,int height,int[] downstream,double[] accumulatedRain,
                           boolean[] sink) {
        public Drainage {
            int size=Math.multiplyExact(width,height);
            if(width<1||height<1||downstream.length!=size||accumulatedRain.length!=size||sink.length!=size)
                throw new IllegalArgumentException("Invalid drainage grid");
            downstream=downstream.clone();accumulatedRain=accumulatedRain.clone();sink=sink.clone();
        }
        @Override public int[] downstream(){return downstream.clone();}
        @Override public double[] accumulatedRain(){return accumulatedRain.clone();}
        @Override public boolean[] sink(){return sink.clone();}
        public int index(int x,int y){
            if(x<0||x>=width||y<0||y>=height)throw new IllegalArgumentException("Invalid cell");
            return y*width+x;
        }
        public int downstreamAt(int x,int y){return downstream[index(x,y)];}
        public double accumulationAt(int x,int y){return accumulatedRain[index(x,y)];}
        public boolean sinkAt(int x,int y){return sink[index(x,y)];}
    }
    public static Drainage route(int width,int height,double[] elevation,double[] rainfall) {
        if(width<1||height<1)throw new IllegalArgumentException("Invalid dimensions");
        int count=Math.multiplyExact(width,height);
        Objects.requireNonNull(elevation);Objects.requireNonNull(rainfall);
        if(elevation.length!=count||rainfall.length!=count)throw new IllegalArgumentException("Invalid grid size");
        int[] downstream=new int[count];
        double[] accumulation=rainfall.clone();
        boolean[] sinks=new boolean[count];
        Integer[] descending=new Integer[count];
        for(int i=0;i<count;i++){
            if(!Double.isFinite(elevation[i])||!Double.isFinite(rainfall[i])||rainfall[i]<0)
                throw new IllegalArgumentException("Invalid elevation or rainfall");
            descending[i]=i;
        }
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int index=y*width+x,best=-1;
            double bestSlope=0;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                if(dx==0&&dy==0)continue;
                int nx=x+dx,ny=y+dy;
                if(nx<0||ny<0||nx>=width||ny>=height)continue;
                int neighbor=ny*width+nx;
                double slope=(elevation[index]-elevation[neighbor])/(dx!=0&&dy!=0?StrictMath.sqrt(2):1);
                if(slope>bestSlope||(slope==bestSlope&&slope>0&&neighbor<best)){
                    bestSlope=slope;best=neighbor;
                }
            }
            downstream[index]=best;
            sinks[index]=best==-1;
        }
        Arrays.sort(descending,(a,b)->{
            int compare=Double.compare(elevation[b],elevation[a]);
            return compare!=0?compare:Integer.compare(a,b);
        });
        for(int i:descending)if(downstream[i]>=0)accumulation[downstream[i]]+=accumulation[i];
        return new Drainage(width,height,downstream,accumulation,sinks);
    }
    public static Drainage sample(PlanetSurfaceV2 world,PlanetClimateV2 climate,
                                  PlanetRegionGrid.Region region,int divisions){
        Objects.requireNonNull(world);Objects.requireNonNull(climate);Objects.requireNonNull(region);
        if(divisions<1||divisions>256)throw new IllegalArgumentException("Invalid divisions");
        int width=divisions+1,count=width*width;
        double[] elevation=new double[count],rain=new double[count];
        for(int y=0;y<width;y++)for(int x=0;x<width;x++){
            int i=y*width+x;
            var point=PlanetRegionGrid.point(region,x,y,divisions);
            elevation[i]=world.sample(point).elevationMeters();
            rain[i]=climate.sample(point).moisture();
        }
        return route(width,width,elevation,rain);
    }
}
