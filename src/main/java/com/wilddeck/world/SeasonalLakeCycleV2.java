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
            if(!Double.isFinite(initialVolume)||!Double.isFinite(finalVolume)||
               !Double.isFinite(totalInput)||!Double.isFinite(totalEvaporation)||
               !Double.isFinite(totalOverflow)||initialVolume<0||finalVolume<0||
               totalInput<0||totalEvaporation<0||totalOverflow<0)
                throw new IllegalArgumentException("Invalid lake result budget");
            double supplied=initialVolume+totalInput;
            double accounted=finalVolume+totalEvaporation+totalOverflow;
            if(!Double.isFinite(supplied)||!Double.isFinite(accounted)||
               Math.abs(supplied-accounted)>Math.max(1e-8,supplied*1e-10))
                throw new IllegalArgumentException("Unbalanced lake result");
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
            double startingVolume=volume;
            var lake=LakeReservoirV2.step(basin,volume,inflow,period.evaporationDepth());
            double supplied=startingVolume+inflow;
            double accounted=lake.volume()+lake.evaporated()+lake.overflow();
            if(!Double.isFinite(supplied)||!Double.isFinite(accounted)||
               Math.abs(supplied-accounted)>Math.max(1e-8,supplied*1e-10))
                throw new IllegalArgumentException("Seasonal lake water conservation violated");
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
    /**
     * Subdivide each season into equal water/evaporation increments. This
     * reduces the start-of-step wetted-area bias for filling or drying lakes.
     * Each returned Season still represents one full input period. Cumulative
     * inflow targets avoid losing a rounded remainder across many substeps,
     * and every period is checked for storage + evaporation + spill balance.
     *
     * This is a numerical approximation, not a climate-calibrated timestep.
     */
    public static Result simulateSubsteps(LakeReservoirV2.Basin basin,double initialVolume,
                                          double contributingArea,Collection<Period> periods,
                                          int substeps) {
        Objects.requireNonNull(basin);
        Objects.requireNonNull(periods);
        if(substeps<1||substeps>4096)
            throw new IllegalArgumentException("Invalid seasonal substep count");
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
            double startingVolume=volume;
            double evaporatedThisSeason=0,spilledThisSeason=0;
            double delivered=0;
            for(int i=0;i<substeps;i++){
                // Cumulative targets ensure the final substep receives the
                // remaining input, rather than accumulating rounded portions.
                double target=inflow*((double)(i+1)/substeps);
                double portion=target-delivered;
                delivered=target;
                var step=LakeReservoirV2.step(basin,volume,portion,
                                              period.evaporationDepth()/substeps);
                volume=step.volume();
                evaporatedThisSeason+=step.evaporated();
                spilledThisSeason+=step.overflow();
            }
            double available=startingVolume+inflow;
            double accounted=volume+evaporatedThisSeason+spilledThisSeason;
            if(!Double.isFinite(available)||!Double.isFinite(accounted)||
               Math.abs(available-accounted)>Math.max(1e-8,available*1e-10))
                throw new IllegalArgumentException("Seasonal lake water conservation violated");
            input+=inflow;
            evaporation+=evaporatedThisSeason;
            overflow+=spilledThisSeason;
            if(!Double.isFinite(input)||!Double.isFinite(evaporation)||
               !Double.isFinite(overflow))
                throw new IllegalArgumentException("Seasonal totals overflow");
            seasons.add(new Season(runoff,new LakeReservoirV2.Step(
                volume,basin.stageAt(volume),evaporatedThisSeason,spilledThisSeason)));
        }
        return new Result(seasons,initialVolume,volume,input,evaporation,overflow);
    }

}
