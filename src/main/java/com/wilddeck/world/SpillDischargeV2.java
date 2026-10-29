package com.wilddeck.world;

import java.util.*;

/**
 * Accumulates rainfall along the acyclic priority-flood spill forest.
 * Boundary cells are outlets; this is a finite-mosaic water balance, not
 * globally calibrated discharge or physical lake storage.
 */
public final class SpillDischargeV2 {
    private SpillDischargeV2(){}
    public record Discharge(int width,int height,double[] flow,int[] outlet) {
        public Discharge {
            if(width<1||height<1||flow.length!=width*height||outlet.length!=width*height)
                throw new IllegalArgumentException("Invalid discharge grid");
            flow=flow.clone();outlet=outlet.clone();
        }
        @Override public double[] flow(){return flow.clone();}
        @Override public int[] outlet(){return outlet.clone();}
    }
    public static Discharge accumulate(LakeSpillV2.Result lakes,double[] rainfall){
        Objects.requireNonNull(lakes);Objects.requireNonNull(rainfall);
        int width=lakes.width(),height=lakes.height(),size=width*height;
        if(rainfall.length!=size)throw new IllegalArgumentException("Invalid rainfall grid");
        int[] next=lakes.spillTo();
        double[] flow=rainfall.clone();
        for(double value:flow)if(!Double.isFinite(value)||value<0)
            throw new IllegalArgumentException("Invalid rainfall");
        int[] remaining=new int[size];
        for(int i=0;i<size;i++){
            if(next[i]<-1||next[i]>=size||next[i]==i)
                throw new IllegalArgumentException("Invalid spill link");
            if(next[i]>=0)remaining[next[i]]++;
        }
        var queue=new ArrayDeque<Integer>();
        for(int i=0;i<size;i++)if(remaining[i]==0)queue.add(i);
        int[] outlet=new int[size];Arrays.fill(outlet,-1);
        int visited=0;
        // Child-to-parent topological traversal; every cell contributes once.
        while(!queue.isEmpty()){
            int i=queue.remove();visited++;
            int downstream=next[i];
            if(downstream>=0){
                flow[downstream]+=flow[i];
                if(--remaining[downstream]==0)queue.add(downstream);
            }
        }
        if(visited!=size)throw new IllegalArgumentException("Spill network contains cycle");
        // Resolve terminal outlet identities with path compression.
        for(int i=0;i<size;i++){
            if(outlet[i]>=0)continue;
            var path=new ArrayList<Integer>();
            int cursor=i;
            while(cursor>=0&&outlet[cursor]<0){
                path.add(cursor);
                cursor=next[cursor];
            }
            int terminal=cursor<0?path.get(path.size()-1):outlet[cursor];
            for(int node:path)outlet[node]=terminal;
        }
        return new Discharge(width,height,flow,outlet);
    }
}
