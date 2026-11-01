package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic, finite-volume degree-day snow and soil bucket prototype.
 * All per-cell quantities are water-equivalent depths in meters, not m3.
 * Convert runoff depth to volume with an independently supplied cell area
 * before routing; never also inject the original precipitation as rainfall.
 *
 * Snowfall occurs at <=0 C; above 0 C snow melts at a caller-supplied
 * degree-day rate. Potential evaporation draws from liquid and soil, not
 * snow. Remaining liquid fills the soil bucket before becoming runoff.
 * This is NOT calibrated snow physics, groundwater or delayed river routing.
 */
public final class SeasonalSnowSoilCycleV2 {
    private SeasonalSnowSoilCycleV2(){}

    public record State(double[] snowDepth,double[] soilDepth) {
        public State {
            Objects.requireNonNull(snowDepth);
            Objects.requireNonNull(soilDepth);
            if(snowDepth.length==0||snowDepth.length!=soilDepth.length)
                throw new IllegalArgumentException("Invalid snow/soil grid");
            snowDepth=snowDepth.clone();
            soilDepth=soilDepth.clone();
            for(int i=0;i<snowDepth.length;i++){
                nonnegative(snowDepth[i]);
                nonnegative(soilDepth[i]);
            }
        }
        @Override public double[] snowDepth(){return snowDepth.clone();}
        @Override public double[] soilDepth(){return soilDepth.clone();}
    }

    public record Period(double[] precipitationDepth,double[] temperatureC,
                         double[] potentialEvaporationDepth,double[] soilCapacityDepth,
                         double days,double degreeDayMeltFactor) {
        public Period {
            Objects.requireNonNull(precipitationDepth);
            Objects.requireNonNull(temperatureC);
            Objects.requireNonNull(potentialEvaporationDepth);
            Objects.requireNonNull(soilCapacityDepth);
            int n=precipitationDepth.length;
            if(n==0||temperatureC.length!=n||potentialEvaporationDepth.length!=n||
               soilCapacityDepth.length!=n||!Double.isFinite(days)||days<=0)
                throw new IllegalArgumentException("Invalid seasonal snow period");
            nonnegative(degreeDayMeltFactor);
            precipitationDepth=precipitationDepth.clone();
            temperatureC=temperatureC.clone();
            potentialEvaporationDepth=potentialEvaporationDepth.clone();
            soilCapacityDepth=soilCapacityDepth.clone();
            for(int i=0;i<n;i++){
                nonnegative(precipitationDepth[i]);
                if(!Double.isFinite(temperatureC[i]))
                    throw new IllegalArgumentException("Nonfinite temperature");
                nonnegative(potentialEvaporationDepth[i]);
                nonnegative(soilCapacityDepth[i]);
            }
        }
        @Override public double[] precipitationDepth(){return precipitationDepth.clone();}
        @Override public double[] temperatureC(){return temperatureC.clone();}
        @Override public double[] potentialEvaporationDepth(){return potentialEvaporationDepth.clone();}
        @Override public double[] soilCapacityDepth(){return soilCapacityDepth.clone();}
    }

    public record Step(State state,double[] snowfallDepth,double[] meltDepth,
                       double[] evaporationDepth,double[] runoffDepth) {
        public Step {
            Objects.requireNonNull(state);
            snowfallDepth=snowfallDepth.clone();
            meltDepth=meltDepth.clone();
            evaporationDepth=evaporationDepth.clone();
            runoffDepth=runoffDepth.clone();
        }
        @Override public double[] snowfallDepth(){return snowfallDepth.clone();}
        @Override public double[] meltDepth(){return meltDepth.clone();}
        @Override public double[] evaporationDepth(){return evaporationDepth.clone();}
        @Override public double[] runoffDepth(){return runoffDepth.clone();}
    }

    public record Cycle(List<Step> periods,State finalState,
                        double[] totalEvaporationDepth,double[] totalRunoffDepth) {
        public Cycle {
            periods=List.copyOf(periods);
            Objects.requireNonNull(finalState);
            totalEvaporationDepth=totalEvaporationDepth.clone();
            totalRunoffDepth=totalRunoffDepth.clone();
        }
        @Override public double[] totalEvaporationDepth(){return totalEvaporationDepth.clone();}
        @Override public double[] totalRunoffDepth(){return totalRunoffDepth.clone();}
    }

    public static Cycle simulate(State initial,Collection<Period> periods) {
        Objects.requireNonNull(initial);
        Objects.requireNonNull(periods);
        int n=initial.snowDepth().length;
        State current=initial;
        var steps=new ArrayList<Step>();
        double[] totalEvap=new double[n],totalRunoff=new double[n];
        for(var period:periods) {
            Objects.requireNonNull(period);
            double[] precipitation=period.precipitationDepth();
            double[] temperature=period.temperatureC();
            double[] potentialEvap=period.potentialEvaporationDepth();
            double[] capacity=period.soilCapacityDepth();
            if(precipitation.length!=n)
                throw new IllegalArgumentException("Seasonal snow grid changed");
            double[] oldSnow=current.snowDepth(),oldSoil=current.soilDepth();
            double[] snow=new double[n],soil=new double[n],snowfall=new double[n],
                     melt=new double[n],evap=new double[n],runoff=new double[n];
            for(int i=0;i<n;i++) {
                snowfall[i]=temperature[i]<=0?precipitation[i]:0;
                double rain=temperature[i]>0?precipitation[i]:0;
                double snowAvailable=finite(oldSnow[i]+snowfall[i]);
                double potentialMelt=temperature[i]>0?
                    finite(finite(temperature[i]*period.days())*period.degreeDayMeltFactor()):0;
                melt[i]=Math.min(snowAvailable,potentialMelt);
                snow[i]=snowAvailable-melt[i];
                double liquid=finite(finite(rain+melt[i])+oldSoil[i]);
                evap[i]=Math.min(liquid,potentialEvap[i]);
                double after=liquid-evap[i];
                soil[i]=Math.min(after,capacity[i]);
                runoff[i]=after-soil[i];
                totalEvap[i]=finite(totalEvap[i]+evap[i]);
                totalRunoff[i]=finite(totalRunoff[i]+runoff[i]);
            }
            current=new State(snow,soil);
            steps.add(new Step(current,snowfall,melt,evap,runoff));
        }
        return new Cycle(steps,current,totalEvap,totalRunoff);
    }

    private static double finite(double value) {
        if(!Double.isFinite(value)||value<0)
            throw new IllegalArgumentException("Snow/soil water overflow");
        return value;
    }
    private static void nonnegative(double value) { finite(value); }
}
