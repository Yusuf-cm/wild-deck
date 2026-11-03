package com.wilddeck.world;

import java.util.*;
import java.math.BigDecimal;

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
                       double erodedMass,double depositedMass,double exportedMass,
                       double importedMass) {
        /** Compatibility constructor for local-erosion-only steps. */
        public Step(double[] terrain,double[] depositionHeight,
                    ErodingWatershedV2.Step eroded,SedimentBudgetV2.Result sediment,
                    LakeSpillV2.Result drainage,SpillDischargeV2.Discharge discharge,
                    double erodedMass,double depositedMass,double exportedMass) {
            this(terrain,depositionHeight,eroded,sediment,drainage,discharge,
                erodedMass,depositedMass,exportedMass,0.0);
        }
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
        return advanceToVerifiedOutlets(width,height,terrain,rainfall,
            cellArea,density,strength,maximumCut,transportCapacity,
            new double[terrain.length],verifiedOutlets);
    }

    /**
     * One-step erosion with authenticated, independently owned imported
     * sediment (kg) delivered to cells. The import is NOT local erosion.
     * Receipt authentication, checkpoint atomicity and changing route
     * topology remain the caller's responsibility.
     */
    public static Step advanceToVerifiedOutlets(
            int width,int height,double[] terrain,double[] rainfall,
            double[] cellArea,double density,double strength,double maximumCut,
            double[] transportCapacity,double[] importedMass,
            Collection<Integer> verifiedOutlets) {
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(rainfall);
        Objects.requireNonNull(cellArea);
        Objects.requireNonNull(transportCapacity);
        Objects.requireNonNull(importedMass);
        Objects.requireNonNull(verifiedOutlets);
        if(importedMass.length!=terrain.length)
            throw new IllegalArgumentException("Invalid imported sediment grid");
        if(!Double.isFinite(density)||density<=0||
           cellArea.length!=terrain.length||
           transportCapacity.length!=terrain.length)
            throw new IllegalArgumentException("Invalid sediment geometry");
        var erosion=ErodingWatershedV2.advanceToVerifiedOutlets(
            width,height,terrain,rainfall,strength,maximumCut,verifiedOutlets);
        double[] mass=SedimentBudgetV2.erodedMass(
            terrain,erosion.elevation(),cellArea,density);
        var routed=SedimentBudgetV2.route(
            erosion.lakes(),mass,importedMass,transportCapacity);
        double[] deposited=routed.deposited(),exported=routed.outletExport();
        double[] result=erosion.elevation(),heightAdded=new double[result.length];
        BigDecimal erodedExact=BigDecimal.ZERO,depositedExact=BigDecimal.ZERO,
            exportedExact=BigDecimal.ZERO,importedExact=BigDecimal.ZERO;
        for(int i=0;i<result.length;i++){
            heightAdded[i]=depositionHeight(result[i],deposited[i],cellArea[i],density);
            result[i]+=heightAdded[i];
            erodedExact=erodedExact.add(BigDecimal.valueOf(mass[i]));
            depositedExact=depositedExact.add(BigDecimal.valueOf(deposited[i]));
            exportedExact=exportedExact.add(BigDecimal.valueOf(exported[i]));
            importedExact=importedExact.add(BigDecimal.valueOf(importedMass[i]));
        }
        BigDecimal source=erodedExact.add(importedExact);
        BigDecimal accounted=depositedExact.add(exportedExact);
        if(source.subtract(accounted).abs().compareTo(
                source.max(BigDecimal.ONE).multiply(BigDecimal.valueOf(1e-9)))>0)
            throw new IllegalArgumentException("Sediment conservation failure");
        double erodedTotal=erodedExact.doubleValue(),
            depositedTotal=depositedExact.doubleValue(),
            exportedTotal=exportedExact.doubleValue(),
            importedTotal=importedExact.doubleValue();
        if(!Double.isFinite(erodedTotal)||!Double.isFinite(depositedTotal)||
           !Double.isFinite(exportedTotal)||!Double.isFinite(importedTotal))
            throw new IllegalArgumentException("Sediment total overflow");
        var drainage=LakeSpillV2.fillToVerifiedOutlets(
            width,height,result,verifiedOutlets);
        var discharge=SpillDischargeV2.accumulate(drainage,rainfall);
        return new Step(result,heightAdded,erosion,routed,drainage,discharge,
                        erodedTotal,depositedTotal,exportedTotal,importedTotal);
    }

    /** Reject deposition that cannot actually change the double-valued bed. */
    static double depositionHeight(double bed,double mass,double area,double density) {
        if(!Double.isFinite(bed)||!Double.isFinite(mass)||mass<0||
           !Double.isFinite(area)||area<=0||
           !Double.isFinite(density)||density<=0)
            throw new IllegalArgumentException("Invalid deposition geometry");
        double denominator=area*density;
        if(!Double.isFinite(denominator)||denominator<=0)
            throw new IllegalArgumentException("Invalid mass-to-height conversion");
        double height=mass/denominator;
        double next=bed+height;
        if(!Double.isFinite(height)||!Double.isFinite(next)||
           (mass>0&&(height==0||next==bed)))
            throw new IllegalArgumentException("Sediment deposition would lose positive mass");
        return height;
    }
}
