package com.wilddeck.world;

import java.util.*;

/**
 * Multi-season finite-volume surface/soil water budget on a fixed grid.
 * Each period's stored water becomes the next period's initial storage.
 * All arrays use the same water-volume units per sample.
 *
 * This is a deterministic bookkeeping model, not calibrated infiltration,
 * groundwater flow, snowpack or spatial runoff routing. River routing must
 * consume the returned runoff once, and lake inflows separately.
 */
public final class SeasonalCatchmentCycleV2 {
    private SeasonalCatchmentCycleV2(){}

    public record Period(double[] rainfall,double[] capacity,double evaporationFraction) {
        public Period {
            Objects.requireNonNull(rainfall);
            Objects.requireNonNull(capacity);
            if(rainfall.length!=capacity.length)
                throw new IllegalArgumentException("Mismatched seasonal grid");
            rainfall=rainfall.clone();
            capacity=capacity.clone();
            if(!Double.isFinite(evaporationFraction)||evaporationFraction<0||
               evaporationFraction>1)
                throw new IllegalArgumentException("Invalid evaporation fraction");
        }
        @Override public double[] rainfall(){return rainfall.clone();}
        @Override public double[] capacity(){return capacity.clone();}
    }

    public record Cycle(List<SeasonalWaterBalanceV2.Balance> periods,
                        double[] runoff,double[] evaporation,double[] finalStorage) {
        public Cycle {
            periods=List.copyOf(periods);
            runoff=runoff.clone();
            evaporation=evaporation.clone();
            finalStorage=finalStorage.clone();
        }
        @Override public double[] runoff(){return runoff.clone();}
        @Override public double[] evaporation(){return evaporation.clone();}
        @Override public double[] finalStorage(){return finalStorage.clone();}
    }

    public static Cycle simulate(double[] initialStorage,Collection<Period> periods) {
        Objects.requireNonNull(initialStorage);
        Objects.requireNonNull(periods);
        int n=initialStorage.length;
        double[] storage=initialStorage.clone();
        for(double value:storage)
            if(!Double.isFinite(value)||value<0)
                throw new IllegalArgumentException("Invalid initial storage");
        double[] runoff=new double[n],evaporation=new double[n];
        var steps=new ArrayList<SeasonalWaterBalanceV2.Balance>();
        for(var period:periods) {
            Objects.requireNonNull(period);
            if(period.rainfall().length!=n)
                throw new IllegalArgumentException("Seasonal grid size changed");
            var step=SeasonalWaterBalanceV2.step(period.rainfall(),storage,
                period.capacity(),period.evaporationFraction());
            var next=step.stored();
            var r=step.remaining();
            var e=step.evaporated();
            for(int i=0;i<n;i++){
                runoff[i]+=r[i];
                evaporation[i]+=e[i];
                if(!Double.isFinite(runoff[i])||!Double.isFinite(evaporation[i]))
                    throw new IllegalArgumentException("Seasonal water overflow");
            }
            storage=next;
            steps.add(step);
        }
        return new Cycle(steps,runoff,evaporation,storage);
    }
}
