package com.wilddeck.world;

import java.util.*;

/**
 * Explicit finite-volume seasonal lake simulation in consistent metric units.
 * Rainfall depth (m) times contributing catchment area (m2) times runoff
 * fraction yields lake inflow (m3). Upstream flow and evaporation use m3 and
 * m respectively. Basin cell areas must also be m2.
 *
 * Runoff fractions, rainfall and evaporation are supplied by the caller.
 * This does not model infiltration, snowpack, groundwater, lagged routing or
 * climate feedback. Use short time steps for the start-of-step evaporation
 * approximation inherited from LakeReservoirV2.
 */
public final class SeasonalLakeCycleV2 {
    private SeasonalLakeCycleV2(){}

    public record Period(double rainfallDepth,double evaporationDepth,
                         double upstreamInflow,double runoffFraction) {
        public Period {
            if(!Double.isFinite(rainfallDepth)||rainfallDepth<0||
               !Double.isFinite(evaporationDepth)||evaporationDepth<0||
               !Double.isFinite(upstreamInflow)||upstreamInflow<0||
               !Double.isFinite(runoffFraction)||runoffFraction<0||runoffFraction>1)
                throw new IllegalArgumentException("Invalid seasonal inputs");
        }
    }

    public record Season(double runoffInflow,LakeReservoirV2.Step lake) {
        public Season {
            if(!Double.isFinite(runoffInflow)||runoffInflow<0)
                throw new IllegalArgumentException("Invalid seasonal runoff");
            Objects.requireNonNull(lake);
        }
    }

    public record Result(List<Season> seasons,double initialVolume,double finalVolume,
                         double totalInput,double totalEvaporation,double totalOverflow) {
        public Result {
            seasons=List.copyOf(seasons);
        }
    }

    public static Result simulate(LakeReservoirV2.Basin basin,double initialVolume,
                                  double contributingArea,Collection<Period> periods) {
        Objects.requireNonNull(basin);
        Objects.requireNonNull(periods);
        if(!Double.isFinite(contributingArea)||contributingArea<0||
           !Double.isFinite(initialVolume)||initialVolume<0||
           initialVolume>basin.capacity())
            throw new IllegalArgumentException("Invalid lake initial state");
        double volume=initialVolume,input=0,evaporation=0,overflow=0;
        var seasons=new ArrayList<Season>();
        for(var period:periods){
            Objects.requireNonNull(period);
            double runoff=period.rainfallDepth()*contributingArea*period.runoffFraction();
            double inflow=runoff+period.upstreamInflow();
            if(!Double.isFinite(runoff)||!Double.isFinite(inflow))
                throw new IllegalArgumentException("Seasonal water overflow");
            var lake=LakeReservoirV2.step(basin,volume,inflow,period.evaporationDepth());
            input+=inflow;
            evaporation+=lake.evaporated();
            overflow+=lake.overflow();
            if(!Double.isFinite(input)||!Double.isFinite(evaporation)||
               !Double.isFinite(overflow))
                throw new IllegalArgumentException("Seasonal totals overflow");
            volume=lake.volume();
            seasons.add(new Season(runoff,lake));
        }
        return new Result(seasons,initialVolume,volume,input,evaporation,overflow);
    }
}
