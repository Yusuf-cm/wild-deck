package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in finite-grid erosion + conservative sediment deposition overlay.
 *
 * Incision produces sediment mass (m3 * density). Transport follows the
 * post-incision spill network; deposited mass raises the receiving bed by
 * mass / (cell area * density). Drainage is recalculated on that new bed.
 * The returned terrain is an overlay: seed terrain and game saves are NOT
 * modified. One fixed-density, one-step approximation, not a calibrated
 * fluvial sediment or multi-year landscape evolution model.
 */
public final class SedimentLandscapeV2 {
    private SedimentLandscapeV2(){}

    public record Step(double[] terrain,double[] depositionHeight,
                       ErodingWatershedV2.Step eroded,
                       SedimentBudgetV2.Result sediment,
                       LakeSpillV2.Result drainage,
                       SpillDischargeV2.Discharge discharge,
                       double erodedMass,double depositedMass,double exportedMass) {
        public Step {
            Objects.requireNonNull(terrain);
            Objects.requireNonNull(depositionHeight);
            Objects.requireNonNull(eroded);
            Objects.requireNonNull(sediment);
            Objects.requireNonNull(drainage);
            Objects.requireNonNull(discharge);
            if(terrain.length!=depositionHeight.length)
                throw new IllegalArgumentException("Invalid landscape result");
            terrain=terrain.clone();
            depositionHeight=depositionHeight.clone();
        }
        @Override public double[] terrain(){return terrain.clone();}
        @Override public double[] depositionHeight(){return depositionHeight.clone();}
    }

    public static Step advanceToVerifiedOutlets(
            int width,int height,double[] terrain,double[] rainfall,
            double[] cellArea,double density,double strength,double maximumCut,
            double[] transportCapacity,Collection<Integer> verifiedOutlets) {
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(rainfall);
        Objects.requireNonNull(cellArea);
        Objects.requireNonNull(transportCapacity);
        Objects.requireNonNull(verifiedOutlets);
        if(!Double.isFinite(density)||density<=0||
           cellArea.length!=terrain.length||
           transportCapacity.length!=terrain.length)
            throw new IllegalArgumentException("Invalid sediment geometry");
        var erosion=ErodingWatershedV2.advanceToVerifiedOutlets(
            width,height,terrain,rainfall,strength,maximumCut,verifiedOutlets);
        double[] mass=SedimentBudgetV2.erodedMass(
            terrain,erosion.elevation(),cellArea,density);
        var routed=SedimentBudgetV2.route(
            erosion.lakes(),mass,transportCapacity);
        double[] deposited=routed.deposited(),exported=routed.outletExport();
        double[] result=erosion.elevation(),heightAdded=new double[result.length];
        double erodedTotal=0,depositedTotal=0,exportedTotal=0;
        for(int i=0;i<result.length;i++){
            double denominator=cellArea[i]*density;
            if(!Double.isFinite(denominator)||denominator<=0)
                throw new IllegalArgumentException("Invalid mass-to-height conversion");
            heightAdded[i]=deposited[i]/denominator;
            result[i]+=heightAdded[i];
            if(!Double.isFinite(result[i])||!Double.isFinite(heightAdded[i]))
                throw new IllegalArgumentException("Sediment terrain overflow");
            erodedTotal+=mass[i];
            depositedTotal+=deposited[i];
            exportedTotal+=exported[i];
        }
        if(!Double.isFinite(erodedTotal)||!Double.isFinite(depositedTotal)||
           !Double.isFinite(exportedTotal)||
           Math.abs(erodedTotal-depositedTotal-exportedTotal)>
                1e-9*Math.max(1,erodedTotal))
            throw new IllegalArgumentException("Sediment conservation failure");
        var drainage=LakeSpillV2.fillToVerifiedOutlets(
            width,height,result,verifiedOutlets);
        var discharge=SpillDischargeV2.accumulate(drainage,rainfall);
        return new Step(result,heightAdded,erosion,routed,drainage,discharge,
                        erodedTotal,depositedTotal,exportedTotal);
    }
}
