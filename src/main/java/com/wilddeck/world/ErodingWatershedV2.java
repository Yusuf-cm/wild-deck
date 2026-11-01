package com.wilddeck.world;

import java.util.*;

/**
 * One finite-grid erosion feedback step: solve priority-flood drainage,
 * estimate bounded river incision, then recompute drainage on modified
 * terrain. All modifications remain in a returned overlay, not seed terrain.
 *
 * This is not a sediment-coupled landscape evolution model. River erosion
 * strength is caller-calibrated and no deposition or climate feedback occurs.
 */
public final class ErodingWatershedV2 {
    private ErodingWatershedV2(){}

    public record Step(double[] elevation,double[] incision,
                       LakeSpillV2.Result lakes,
                       SpillDischargeV2.Discharge discharge) {
        public Step {
            Objects.requireNonNull(elevation);
            Objects.requireNonNull(incision);
            Objects.requireNonNull(lakes);
            Objects.requireNonNull(discharge);
            if(elevation.length!=incision.length||
               elevation.length!=(long)lakes.width()*lakes.height())
                throw new IllegalArgumentException("Invalid erosion result");
            elevation=elevation.clone();
            incision=incision.clone();
        }
        @Override public double[] elevation(){return elevation.clone();}
        @Override public double[] incision(){return incision.clone();}
    }

    public static Step advance(int width,int height,double[] elevation,double[] rainfall,
                               double strength,double maximumCut) {
        return step(width,height,elevation,rainfall,strength,maximumCut,null);
    }

    /**
     * Recalculate erosion and post-incision drainage toward ONLY verified
     * outlets, rather than treating every finite-grid edge as an ocean.
     * Paths through depressions describe spill potential once filled; they
     * do not imply water can pass an empty lake immediately.
     */
    public static Step advanceToVerifiedOutlets(
            int width,int height,double[] elevation,double[] rainfall,
            double strength,double maximumCut,Collection<Integer> outlets) {
        Objects.requireNonNull(outlets);
        return step(width,height,elevation,rainfall,strength,maximumCut,outlets);
    }

    private static Step step(int width,int height,double[] elevation,double[] rainfall,
                             double strength,double maximumCut,Collection<Integer> outlets) {
        Objects.requireNonNull(elevation);
        Objects.requireNonNull(rainfall);
        if(width<1||height<1||(long)width*height!=elevation.length||
           rainfall.length!=elevation.length)
            throw new IllegalArgumentException("Invalid watershed grid");
        var before=outlets==null?LakeSpillV2.fill(width,height,elevation):
            LakeSpillV2.fillToVerifiedOutlets(width,height,elevation,outlets);
        var water=SpillDischargeV2.accumulate(before,rainfall);
        var eroded=RiverErosionV2.erode(elevation,before,water,strength,maximumCut);
        var incision=new double[elevation.length];
        for(int i=0;i<incision.length;i++)
            incision[i]=Math.max(0,elevation[i]-eroded[i]);
        var after=outlets==null?LakeSpillV2.fill(width,height,eroded):
            LakeSpillV2.fillToVerifiedOutlets(width,height,eroded,outlets);
        var rerouted=SpillDischargeV2.accumulate(after,rainfall);
        return new Step(eroded,incision,after,rerouted);
    }
}
