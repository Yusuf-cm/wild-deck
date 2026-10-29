package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic one-step water budget, in consistent arbitrary water-volume
 * units per grid sample. This is a prototype, not a calibrated climate model.
 */
public final class SeasonalWaterBalanceV2 {
    private SeasonalWaterBalanceV2(){}

    public record Balance(double[] remaining,double[] evaporated,double[] stored) {
        public Balance {
            remaining=remaining.clone();evaporated=evaporated.clone();stored=stored.clone();
        }
        @Override public double[] remaining(){return remaining.clone();}
        @Override public double[] evaporated(){return evaporated.clone();}
        @Override public double[] stored(){return stored.clone();}
    }

    /**
     * Each sample starts with rainfall + previous storage. Evaporation is
     * bounded by available water; storage capacity limits carried water.
     * Remaining water is available for river routing.
     */
    public static Balance step(double[] rainfall,double[] previousStorage,
                               double[] storageCapacity,double evaporationFraction){
        Objects.requireNonNull(rainfall);Objects.requireNonNull(previousStorage);
        Objects.requireNonNull(storageCapacity);
        if(rainfall.length!=previousStorage.length||rainfall.length!=storageCapacity.length||
           !Double.isFinite(evaporationFraction)||evaporationFraction<0||evaporationFraction>1)
            throw new IllegalArgumentException("Invalid water budget");
        int n=rainfall.length;
        double[] remaining=new double[n],evaporated=new double[n],stored=new double[n];
        for(int i=0;i<n;i++){
            double rain=rainfall[i],prior=previousStorage[i],capacity=storageCapacity[i];
            if(!Double.isFinite(rain)||!Double.isFinite(prior)||!Double.isFinite(capacity)||
               rain<0||prior<0||capacity<0)
                throw new IllegalArgumentException("Invalid water amount");
            double available=rain+prior;
            if(!Double.isFinite(available))throw new IllegalArgumentException("Water overflow");
            evaporated[i]=available*evaporationFraction;
            double after=available-evaporated[i];
            stored[i]=Math.min(after,capacity);
            remaining[i]=after-stored[i];
        }
        return new Balance(remaining,evaporated,stored);
    }
}
