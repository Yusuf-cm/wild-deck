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
        Objects.requireNonNull(elevation);
        Objects.requireNonNull(rainfall);
        if(width<1||height<1||(long)width*height!=elevation.length||
           rainfall.length!=elevation.length)
            throw new IllegalArgumentException("Invalid watershed grid");
        var before=LakeSpillV2.fill(width,height,elevation);
        var water=SpillDischargeV2.accumulate(before,rainfall);
        var eroded=RiverErosionV2.erode(elevation,before,water,strength,maximumCut);
        var incision=new double[elevation.length];
        for(int i=0;i<incision.length;i++)
            incision[i]=Math.max(0,elevation[i]-eroded[i]);
        var after=LakeSpillV2.fill(width,height,eroded);
        var rerouted=SpillDischargeV2.accumulate(after,rainfall);
        return new Step(eroded,incision,after,rerouted);
    }
}
