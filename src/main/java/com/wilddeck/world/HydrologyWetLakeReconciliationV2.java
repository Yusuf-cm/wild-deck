package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in conservative redistribution when a VERIFIED lake plan changes after
 * erosion or deposition. Unlike the strict anchor-preserving transition, this
 * can split and merge wet lakes. It reconstructs each old lake's water surface
 * from its hypsometry, assigns the water column at every old wet cell to the
 * new basin containing that cell, and equilibrates the resulting volume within
 * each new reservoir. Excess above the new spill stage is reported separately.
 *
 * Water in a cell that ceases to belong to ANY lake is rejected: routing that
 * displaced water over newly exposed terrain requires a separate verified
 * surface-flow solve. This is an instantaneous finite-volume remap, not a
 * transient hydraulic model. The caller must verify both basin plans and
 * deliver the returned overflow exactly once.
 */
public final class HydrologyWetLakeReconciliationV2 {
    private HydrologyWetLakeReconciliationV2() {}

    public static HydrologyLakeGeometryTransitionV2.Result reconcile(
            HydrologyLakeStateV2.State previous,
            HydrologySphericalLakeBasinsV2.Plan oldPlan,
            HydrologySphericalLakeBasinsV2.Plan newPlan) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(oldPlan);
        Objects.requireNonNull(newPlan);
        var oldLakes=index(oldPlan.lakes());
        var newLakes=index(newPlan.lakes());
        var oldOwners=owners(oldLakes);
        var newOwners=owners(newLakes);
        if(!oldOwners.isEmpty()&&!newOwners.isEmpty()) {
            var a=oldOwners.keySet().iterator().next();
            var b=newOwners.keySet().iterator().next();
            if(a.level()!=b.level()||a.divisions()!=b.divisions())
                throw new IllegalArgumentException("Mixed lake sampling resolution");
        }
        var inflow=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        double initial=0;
        for(var entry:previous.volumes().entrySet()) {
            var old=oldLakes.get(entry.getKey());
            if(old==null)
                throw new IllegalArgumentException("Unknown previous lake identity");
            double volume=entry.getValue();
            var basin=old.reservoir();
            if(volume>basin.capacity())
                throw new IllegalArgumentException("Previous lake exceeds capacity");
            initial=add(initial,volume);
            if(volume==0)continue;
            double stage=basin.stageAt(volume);
            var beds=basin.bedElevation();
            var areas=basin.cellArea();
            double[] columns=new double[beds.length];
            double sum=0;
            int lastWet=-1;
            for(int i=0;i<beds.length;i++) {
                columns[i]=Math.max(0,stage-beds[i])*areas[i];
                sum=add(sum,columns[i]);
                if(columns[i]>0)lastWet=i;
            }
            if(lastWet<0||!(sum>0)||!Double.isFinite(sum))
                throw new IllegalArgumentException("Cannot reconstruct wet lake columns");
            double allocated=0;
            for(int i=0;i<columns.length;i++) {
                if(columns[i]==0)continue;
                double portion=i==lastWet?volume-allocated:volume*(columns[i]/sum);
                if(!Double.isFinite(portion)||portion<0)
                    throw new IllegalArgumentException("Unstable wet lake redistribution");
                allocated=add(allocated,portion);
                var owner=newOwners.get(old.cells().get(i));
                if(owner==null)
                    throw new IllegalArgumentException(
                        "Wet lake cell disappeared without a verified surface route");
                inflow.merge(owner,portion,HydrologyWetLakeReconciliationV2::add);
            }
        }
        var stored=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var overflow=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        double finalStorage=0,released=0;
        for(var entry:newLakes.entrySet()) {
            var lake=entry.getValue();
            double water=inflow.getOrDefault(entry.getKey(),0.0);
            double capacity=lake.reservoir().capacity();
            double kept=Math.min(water,capacity);
            double excess=water-kept;
            stored.put(entry.getKey(),kept);
            finalStorage=add(finalStorage,kept);
            released=add(released,excess);
            if(excess>0)
                overflow.merge(lake.spillTarget(),excess,
                    HydrologyWetLakeReconciliationV2::add);
        }
        double tolerance=Math.max(1e-8,initial*1e-10);
        if(!Double.isFinite(initial)||!Double.isFinite(finalStorage)||
           !Double.isFinite(released)||
           Math.abs(initial-(finalStorage+released))>tolerance)
            throw new IllegalArgumentException("Wet lake remap violated conservation");
        return new HydrologyLakeGeometryTransitionV2.Result(
            new HydrologyLakeStateV2.State(previous.world(),stored),
            overflow,initial,finalStorage,released);
    }

    private static TreeMap<GlobalHydrologyAddressV2.Sample,
                           HydrologySphericalLakeBasinsV2.Lake> index(
            List<HydrologySphericalLakeBasinsV2.Lake> lakes) {
        var result=new TreeMap<GlobalHydrologyAddressV2.Sample,
                               HydrologySphericalLakeBasinsV2.Lake>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var lake:lakes) {
            Objects.requireNonNull(lake);
            if(!HydrologySphericalTopologyV2.isCanonical(lake.anchor())||
               result.putIfAbsent(lake.anchor(),lake)!=null)
                throw new IllegalArgumentException("Invalid lake anchor");
            var cells=lake.cells();
            if(cells.size()!=lake.reservoir().bedElevation().length||
               !cells.get(0).equals(lake.anchor()))
                throw new IllegalArgumentException("Lake cell geometry mismatch");
            for(int i=0;i<cells.size();i++) {
                var cell=cells.get(i);
                if(!HydrologySphericalTopologyV2.isCanonical(cell)||
                   cell.level()!=lake.anchor().level()||
                   cell.divisions()!=lake.anchor().divisions()||
                   (i>0&&HydrologyFlowGraphV2.ADDRESS_ORDER.compare(
                       cells.get(i-1),cell)>=0))
                    throw new IllegalArgumentException("Lake cells must be canonical and sorted");
            }
            if(!Double.isFinite(lake.reservoir().capacity()))
                throw new IllegalArgumentException("Invalid reservoir capacity");
        }
        return result;
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
                       GlobalHydrologyAddressV2.Sample> owners(
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySphericalLakeBasinsV2.Lake> lakes) {
        var result=new TreeMap<GlobalHydrologyAddressV2.Sample,
                               GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var lake:lakes.values())
            for(var cell:lake.cells())
                if(result.putIfAbsent(cell,lake.anchor())!=null)
                    throw new IllegalArgumentException("Overlapping lake cells");
        return result;
    }

    private static double add(double a,double b) {
        double result=a+b;
        if(!Double.isFinite(result)||result<0)
            throw new IllegalArgumentException("Lake volume overflow");
        return result;
    }
}
