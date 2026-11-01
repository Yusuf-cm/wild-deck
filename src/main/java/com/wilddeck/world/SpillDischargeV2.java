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
            for(int i=0;i<flow.length;i++) {
                if(!Double.isFinite(flow[i])||flow[i]<0||
                   outlet[i]<0||outlet[i]>=flow.length)
                    throw new IllegalArgumentException("Invalid discharge or outlet");
            }
        }
        @Override public double[] flow(){return flow.clone();}
        @Override public int[] outlet(){return outlet.clone();}
    }
    public static Discharge accumulate(LakeSpillV2.Result lakes,double[] rainfall){
        return accumulate(lakes,rainfall,new double[rainfall.length]);
    }
    /** Adds nonnegative incoming water at globally reconciled grid samples. */
    public static Discharge accumulate(LakeSpillV2.Result lakes,double[] rainfall,double[] incoming){
        Objects.requireNonNull(lakes);Objects.requireNonNull(rainfall);Objects.requireNonNull(incoming);
        int width=lakes.width(),height=lakes.height(),size=width*height;
        if(rainfall.length!=size||incoming.length!=size)throw new IllegalArgumentException("Invalid water input grid");
        int[] next=lakes.spillTo();
        double[] flow=new double[size];
        for(int i=0;i<size;i++){
            if(!Double.isFinite(rainfall[i])||rainfall[i]<0||!Double.isFinite(incoming[i])||incoming[i]<0)
                throw new IllegalArgumentException("Invalid water input");
            flow[i]=rainfall[i]+incoming[i];
            if(!Double.isFinite(flow[i]))throw new IllegalArgumentException("Water input overflow");
        }
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
                double sum=flow[downstream]+flow[i];
                if(!Double.isFinite(sum))
                    throw new IllegalArgumentException("Downstream discharge overflow");
                flow[downstream]=sum;
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
