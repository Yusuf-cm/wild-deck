package com.wilddeck.world;

import java.util.*;

/**
 * Stable geographic sample addresses independent of a mosaic's origin/extent.
 * These identify locations, NOT global river catchments or hydrologic outlets.
 */
public final class GlobalHydrologyAddressV2 {
    private GlobalHydrologyAddressV2(){}

    public record Sample(int level,int divisions,long x,long y) {
        public Sample {
            if(level<0||level>30||divisions<1||divisions>128)
                throw new IllegalArgumentException("Invalid sample resolution");
            long maxX=(2L<<level)*divisions;
            long maxY=(1L<<level)*divisions;
            if(x<0||x>maxX||y<0||y>maxY)
                throw new IllegalArgumentException("Sample outside planet");
        }
    }

    public static Sample at(int level,int divisions,long firstColumn,long firstRow,
                            int width,int height,int index){
        if(width<1||height<1||index<0||index>=(long)width*height)
            throw new IllegalArgumentException("Invalid mosaic sample");
        if(firstColumn<0||firstRow<0)
            throw new IllegalArgumentException("Invalid mosaic origin");
        long x=Math.addExact(Math.multiplyExact(firstColumn,divisions),index%width);
        long y=Math.addExact(Math.multiplyExact(firstRow,divisions),index/width);
        return new Sample(level,divisions,x,y);
    }

    public record Channel(Sample sample,Sample downstream,double flow) {
        public Channel {
            Objects.requireNonNull(sample);
            Objects.requireNonNull(downstream);
            if(!Double.isFinite(flow)||flow<0)throw new IllegalArgumentException("Invalid flow");
        }
    }

    public static List<Channel> channels(RiverNetworkV2.Network rivers,
                                          int level,int divisions,
                                          long firstColumn,long firstRow){
        Objects.requireNonNull(rivers);
        var result=new ArrayList<Channel>();
        for(var cell:rivers.channels()){
            result.add(new Channel(
                at(level,divisions,firstColumn,firstRow,rivers.width(),rivers.height(),cell.index()),
                at(level,divisions,firstColumn,firstRow,rivers.width(),rivers.height(),cell.downstream()),
                cell.flow()));
        }
        return List.copyOf(result);
    }
}
