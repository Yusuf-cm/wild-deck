package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic channel extraction from a finite connected drainage mosaic.
 * A river ID is the index of its terminal outlet in this mosaic's grid.
 * IDs are stable for a fixed mosaic origin, extent, resolution and seed,
 * but are NOT globally stable across differently sized mosaics.
 */
public final class RiverNetworkV2 {
    private RiverNetworkV2(){}
    public record RiverCell(int index,int downstream,int outlet,double flow) {
        public RiverCell {
            if(index<0||downstream< -1||outlet<0||!Double.isFinite(flow)||flow<0)
                throw new IllegalArgumentException("Invalid river cell");
        }
    }
    public record Network(int width,int height,List<RiverCell> channels) {
        public Network { channels=List.copyOf(channels); }
    }
    public static Network extract(RegionalDrainageV2.Drainage drainage,double minFlow){
        Objects.requireNonNull(drainage);
        if(!Double.isFinite(minFlow)||minFlow<=0)throw new IllegalArgumentException("Invalid flow threshold");
        int width=drainage.width(),height=drainage.height(),size=width*height;
        int[] next=drainage.downstream();
        double[] rain=drainage.accumulatedRain();
        int[] outlets=new int[size];Arrays.fill(outlets,-1);
        for(int i=0;i<size;i++){
            if(outlets[i]>=0)continue;
            var path=new ArrayList<Integer>();
            int cursor=i;
            while(cursor>=0&&outlets[cursor]<0){
                path.add(cursor);
                if(path.size()>size)throw new IllegalArgumentException("Drainage contains a cycle");
                cursor=next[cursor];
            }
            int outlet=cursor<0?path.get(path.size()-1):outlets[cursor];
            for(int node:path)outlets[node]=outlet;
        }
        var channels=new ArrayList<RiverCell>();
        for(int i=0;i<size;i++){
            if(rain[i]>=minFlow&&next[i]>=0)
                channels.add(new RiverCell(i,next[i],outlets[i],rain[i]));
        }
        return new Network(width,height,channels);
    }
}
