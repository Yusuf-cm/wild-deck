package com.wilddeck.world;

import java.util.*;

/**
 * Conservative sediment routing along an acyclic lake-spill drainage forest.
 * Each cell receives eroded mass and upstream transported sediment, deposits
 * any mass exceeding local transport capacity, and forwards the remainder.
 * Outlet transport is exported; it is not silently destroyed.
 *
 * This is a one-step mass budget. Transport capacity, cell areas, sediment
 * density and deposition effects on terrain are not yet climate-calibrated.
 */
public final class SedimentBudgetV2 {
    private SedimentBudgetV2() {}

    public record Result(double[] deposited,double[] transported,double[] outletExport) {
        public Result {
            deposited=deposited.clone();
            transported=transported.clone();
            outletExport=outletExport.clone();
        }
        @Override public double[] deposited(){return deposited.clone();}
        @Override public double[] transported(){return transported.clone();}
        @Override public double[] outletExport(){return outletExport.clone();}
    }

    public static double[] erodedMass(double[] before,double[] after,
                                       double[] cellArea,double density) {
        Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(cellArea);
        if(before.length!=after.length||before.length!=cellArea.length||
           !Double.isFinite(density)||density<=0)
            throw new IllegalArgumentException("Invalid erosion geometry");
        double[] mass=new double[before.length];
        for(int i=0;i<mass.length;i++){
            if(!Double.isFinite(before[i])||!Double.isFinite(after[i])||
               !Double.isFinite(cellArea[i])||cellArea[i]<=0||
               after[i]>before[i])
                throw new IllegalArgumentException("Invalid incision");
            mass[i]=(before[i]-after[i])*cellArea[i]*density;
            if(!Double.isFinite(mass[i]))throw new IllegalArgumentException("Sediment overflow");
        }
        return mass;
    }

    public static Result route(LakeSpillV2.Result lakes,double[] erodedMass,
                               double[] transportCapacity) {
        Objects.requireNonNull(lakes);Objects.requireNonNull(erodedMass);
        Objects.requireNonNull(transportCapacity);
        int size=Math.multiplyExact(lakes.width(),lakes.height());
        if(erodedMass.length!=size||transportCapacity.length!=size)
            throw new IllegalArgumentException("Invalid sediment grid");
        int[] next=lakes.spillTo(),remaining=new int[size];
        double[] incoming=new double[size],deposited=new double[size],
                 transported=new double[size],exported=new double[size];
        for(int i=0;i<size;i++){
            if(!Double.isFinite(erodedMass[i])||erodedMass[i]<0||
               !Double.isFinite(transportCapacity[i])||transportCapacity[i]<0||
               next[i]<-1||next[i]>=size||next[i]==i)
                throw new IllegalArgumentException("Invalid sediment input or route");
            if(next[i]>=0)remaining[next[i]]++;
        }
        var queue=new ArrayDeque<Integer>();
        for(int i=0;i<size;i++)if(remaining[i]==0)queue.add(i);
        int visited=0;
        while(!queue.isEmpty()){
            int i=queue.remove();visited++;
            double available=incoming[i]+erodedMass[i];
            if(!Double.isFinite(available))throw new IllegalArgumentException("Sediment overflow");
            transported[i]=Math.min(available,transportCapacity[i]);
            deposited[i]=available-transported[i];
            int downstream=next[i];
            if(downstream>=0){
                incoming[downstream]+=transported[i];
                if(!Double.isFinite(incoming[downstream]))
                    throw new IllegalArgumentException("Sediment overflow");
                if(--remaining[downstream]==0)queue.add(downstream);
            } else exported[i]=transported[i];
        }
        if(visited!=size)throw new IllegalArgumentException("Sediment route contains cycle");
        return new Result(deposited,transported,exported);
    }
}
