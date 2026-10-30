package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyWetLakeReconciliationV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(3,2);
    private static final GlobalHydrologyAddressV2.Sample B=at(4,2);
    private static final GlobalHydrologyAddressV2.Sample OUT=at(5,2);

    private static HydrologySphericalLakeBasinsV2.Lake lake(
            GlobalHydrologyAddressV2.Sample anchor,
            List<GlobalHydrologyAddressV2.Sample> cells,
            double[] beds,double[] areas,double spill) {
        return new HydrologySphericalLakeBasinsV2.Lake(
            anchor,cells,OUT,OUT,new LakeReservoirV2.Basin(beds,areas,spill));
    }
    private static HydrologySphericalLakeBasinsV2.Plan plan(
            HydrologySphericalLakeBasinsV2.Lake... lakes) {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(),List.of(lakes));
    }

    @Test void splitsWetLakeByActualOldWaterColumnsWithoutCreatingWater() {
        var old=plan(lake(A,List.of(A,B),new double[]{0,2},
            new double[]{1,1},10));
        var split=plan(
            lake(A,List.of(A),new double[]{0},new double[]{1},10),
            lake(B,List.of(B),new double[]{2},new double[]{1},10));
        // At stage 6 the old columns hold 6 and 4 volume units.
        var state=new HydrologyLakeStateV2.State("world",Map.of(A,10.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeGeometryTransitionV2.reconcile(state,old,split));
        var result=HydrologyWetLakeReconciliationV2.reconcile(state,old,split);
        assertEquals(6,result.state().volumes().get(A),1e-9);
        assertEquals(4,result.state().volumes().get(B),1e-9);
        assertEquals(10,result.initialStorage(),1e-9);
        assertEquals(10,result.finalStorage(),1e-9);
        assertEquals(0,result.released(),0);
        assertTrue(result.overflow().isEmpty());
        assertEquals(result.state(),HydrologyLakeStateV2.decode(
            HydrologyLakeStateV2.encode(result.state())));
    }

    @Test void mergesTwoWetLakesAndEquilibratesCombinedVolume() {
        var old=plan(
            lake(A,List.of(A),new double[]{0},new double[]{1},10),
            lake(B,List.of(B),new double[]{0},new double[]{1},10));
        var merged=plan(lake(A,List.of(A,B),new double[]{0,0},
            new double[]{1,1},10));
        var state=new HydrologyLakeStateV2.State("world",
            Map.of(A,6.0,B,7.0));
        var result=HydrologyWetLakeReconciliationV2.reconcile(state,old,merged);
        assertEquals(13,result.state().volumes().get(A),1e-9);
        assertEquals(13,result.initialStorage(),1e-9);
        assertEquals(13,result.finalStorage(),1e-9);
        assertEquals(0,result.released(),0);
        assertEquals(6.5,merged.lakes().get(0).reservoir().stageAt(13),1e-9);
    }

    @Test void sedimentReductionReleasesOnlyExcessToNewSpill() {
        var old=plan(lake(A,List.of(A,B),new double[]{0,0},
            new double[]{1,1},10));
        var narrowed=plan(lake(A,List.of(A,B),new double[]{0,0},
            new double[]{0.25,0.25},10));
        var result=HydrologyWetLakeReconciliationV2.reconcile(
            new HydrologyLakeStateV2.State("world",Map.of(A,16.0)),
            old,narrowed);
        assertEquals(16,result.initialStorage(),1e-9);
        assertEquals(5,result.finalStorage(),1e-9);
        assertEquals(11,result.released(),1e-9);
        assertEquals(11,result.overflow().get(OUT),1e-9);
        assertEquals(16,result.finalStorage()+result.released(),1e-9);
    }

    @Test void disappearingWetCellsAndOverlappingNewBasinsFailClosed() {
        var old=plan(lake(A,List.of(A,B),new double[]{0,0},
            new double[]{1,1},10));
        var missing=plan(lake(A,List.of(A),new double[]{0},
            new double[]{1},10));
        var wet=new HydrologyLakeStateV2.State("world",Map.of(A,10.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyWetLakeReconciliationV2.reconcile(wet,old,missing));
        var overlapping=plan(
            lake(A,List.of(A,B),new double[]{0,0},new double[]{1,1},10),
            lake(B,List.of(B),new double[]{0},new double[]{1},10));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyWetLakeReconciliationV2.reconcile(wet,old,overlapping));
        var dry=HydrologyWetLakeReconciliationV2.reconcile(
            new HydrologyLakeStateV2.State("world",Map.of(A,0.0)),old,missing);
        assertEquals(0,dry.finalStorage(),0);
    }

    @Test void identicalPlansAreOrderIndependentAndPreservePartialStorage() {
        var old=plan(
            lake(A,List.of(A),new double[]{-5},new double[]{3},10),
            lake(B,List.of(B),new double[]{0},new double[]{2},10));
        var reversed=plan(old.lakes().get(1),old.lakes().get(0));
        var state=new HydrologyLakeStateV2.State("world",Map.of(A,17.0,B,4.0));
        var first=HydrologyWetLakeReconciliationV2.reconcile(state,old,old);
        var second=HydrologyWetLakeReconciliationV2.reconcile(state,old,reversed);
        assertEquals(first,second);
        assertEquals(21,first.finalStorage(),1e-9);
        assertEquals(0,first.released(),0);
    }
}
