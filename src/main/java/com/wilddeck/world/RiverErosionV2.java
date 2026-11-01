package com.wilddeck.world;

import java.util.*;

/**
 * Bounded, deterministic river incision overlay. A first-order prototype:
 * erosion depends on local spill-route descent and flow, with no sediment
 * transport, deposition, or iterative terrain/hydrology feedback.
 */
public final class RiverErosionV2 {
    private RiverErosionV2(){}

    public static double[] erode(double[] elevation,LakeSpillV2.Result lakes,
                                 SpillDischargeV2.Discharge discharge,
                                 double strength,double maximumCut){
        Objects.requireNonNull(elevation);Objects.requireNonNull(lakes);
        Objects.requireNonNull(discharge);
        int size=lakes.width()*lakes.height();
        if(elevation.length!=size||discharge.width()!=lakes.width()||
           discharge.height()!=lakes.height()||!Double.isFinite(strength)||
           !Double.isFinite(maximumCut)||strength<0||maximumCut<0)
            throw new IllegalArgumentException("Invalid erosion parameters");
        double[] result=elevation.clone();
        double[] flow=discharge.flow();
        int[] next=lakes.spillTo();
        for(int i=0;i<size;i++){
            if(!Double.isFinite(elevation[i]))
                throw new IllegalArgumentException("Invalid elevation");
            if(next[i]<0)continue;
            if(!Double.isFinite(flow[i])||flow[i]<0)
                throw new IllegalArgumentException("Invalid river discharge");
            double drop=Math.max(0,elevation[i]-elevation[next[i]]);
            if(!Double.isFinite(drop))
                throw new IllegalArgumentException("Invalid river descent");
            double potential=strength*Math.log1p(flow[i])*drop;
            if(!Double.isFinite(potential))
                throw new IllegalArgumentException("River incision overflow");
            // Never cut below the downstream bed in a single step. This
            // avoids creating an artificial uphill reach from over-incision.
            double cut=Math.min(drop,Math.min(maximumCut,potential));
            result[i]=elevation[i]-cut;
        }
        return result;
    }
}
