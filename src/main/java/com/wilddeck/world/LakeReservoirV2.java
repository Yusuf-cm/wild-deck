package com.wilddeck.world;

import java.util.*;

/**
 * Finite-volume, hypsometric reservoir for one known lake basin.
 *
 * Cell bed heights and horizontal areas define V(stage) = sum of submerged
 * column volumes. The spill elevation caps storage; excess water overflows.
 * The caller must supply a physically connected basin and real cell areas.
 * This does not yet discover lake basins or model groundwater/sediment.
 */
public final class LakeReservoirV2 {
    private LakeReservoirV2() {}

    public record Basin(double[] bedElevation,double[] cellArea,double spillElevation) {
        public Basin {
            Objects.requireNonNull(bedElevation);
            Objects.requireNonNull(cellArea);
            if (bedElevation.length==0 || bedElevation.length!=cellArea.length ||
                !Double.isFinite(spillElevation))
                throw new IllegalArgumentException("Invalid lake geometry");
            bedElevation=bedElevation.clone();
            cellArea=cellArea.clone();
            boolean hasStorage=false;
            for (int i=0;i<bedElevation.length;i++) {
                if (!Double.isFinite(bedElevation[i]) ||
                    !Double.isFinite(cellArea[i]) || cellArea[i]<=0)
                    throw new IllegalArgumentException("Invalid lake cell");
                if (bedElevation[i]<spillElevation) hasStorage=true;
            }
            if (!hasStorage) throw new IllegalArgumentException("No volume below spill");
        }
        @Override public double[] bedElevation(){return bedElevation.clone();}
        @Override public double[] cellArea(){return cellArea.clone();}
        public double capacity(){return volumeAt(spillElevation);}
        public double minimumBed() {
            double minimum=Double.POSITIVE_INFINITY;
            for(double elevation:bedElevation)minimum=Math.min(minimum,elevation);
            return minimum;
        }
        public double volumeAt(double stage) {
            if(!Double.isFinite(stage))throw new IllegalArgumentException("Invalid stage");
            double volume=0;
            for(int i=0;i<bedElevation.length;i++)
                volume+=Math.max(0,Math.min(stage,spillElevation)-bedElevation[i])*cellArea[i];
            if(!Double.isFinite(volume))throw new IllegalArgumentException("Lake volume overflow");
            return volume;
        }
        public double wettedArea(double stage) {
            if(!Double.isFinite(stage))throw new IllegalArgumentException("Invalid stage");
            double area=0;
            for(int i=0;i<bedElevation.length;i++)
                if(bedElevation[i]<stage)area+=cellArea[i];
            if(!Double.isFinite(area))throw new IllegalArgumentException("Lake area overflow");
            return area;
        }
        public double stageAt(double volume) {
            double capacity=capacity();
            if(!Double.isFinite(volume)||volume<0||volume>capacity)
                throw new IllegalArgumentException("Volume outside reservoir");
            if(volume==0)return minimumBed();
            if(volume==capacity)return spillElevation;
            double low=minimumBed(),high=spillElevation;
            for(int i=0;i<64;i++){
                double middle=low+(high-low)*0.5;
                if(volumeAt(middle)<volume)low=middle;
                else high=middle;
            }
            return low+(high-low)*0.5;
        }
    }

    public record Step(double volume,double stage,double evaporated,double overflow) {
        public Step {
            if(!Double.isFinite(volume)||!Double.isFinite(stage)||
               !Double.isFinite(evaporated)||!Double.isFinite(overflow)||
               volume<0||evaporated<0||overflow<0)
                throw new IllegalArgumentException("Invalid reservoir step");
        }
    }

    /**
     * Evaporation depth is multiplied by the wetted surface at the *start*
     * of the time step. This explicit approximation is conservative in
     * volume but requires sufficiently short steps for realistic dynamics.
     */
    public static Step step(Basin basin,double previousVolume,
                            double inflow,double evaporationDepth) {
        Objects.requireNonNull(basin);
        double capacity=basin.capacity();
        if(!Double.isFinite(previousVolume)||!Double.isFinite(inflow)||
           !Double.isFinite(evaporationDepth)||previousVolume<0||
           previousVolume>capacity||inflow<0||evaporationDepth<0)
            throw new IllegalArgumentException("Invalid water budget");
        double available=previousVolume+inflow;
        if(!Double.isFinite(available))throw new IllegalArgumentException("Water overflow");
        double potential=evaporationDepth*basin.wettedArea(basin.stageAt(previousVolume));
        double evaporated=Math.min(available,potential);
        double after=available-evaporated;
        double stored=Math.min(capacity,after);
        double overflow=after-stored;
        return new Step(stored,basin.stageAt(stored),evaporated,overflow);
    }
}
