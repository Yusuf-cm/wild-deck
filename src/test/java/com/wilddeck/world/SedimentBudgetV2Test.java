package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SedimentBudgetV2Test {
    @Test void sedimentDepositsAndExportsWithoutLosingMass() {
        var spill=new LakeSpillV2.Result(3,1,new double[]{3,2,1},
                                         new int[]{1,2,-1});
        var result=SedimentBudgetV2.route(spill,new double[]{5,3,0},
                                           new double[]{4,5,10});
        assertArrayEquals(new double[]{1,2,0},result.deposited(),1e-9);
        assertArrayEquals(new double[]{4,5,5},result.transported(),1e-9);
        assertArrayEquals(new double[]{0,0,5},result.outletExport(),1e-9);
        double mass=0;
        for(double d:result.deposited())mass+=d;
        for(double e:result.outletExport())mass+=e;
        assertEquals(8,mass,1e-9);
    }
    @Test void erosionVolumeConvertsToPhysicalMass() {
        assertArrayEquals(new double[]{4000,0},
            SedimentBudgetV2.erodedMass(new double[]{10,5},new double[]{9,5},
                                        new double[]{2,2},2000),1e-9);
    }
    @Test void rejectsCyclesAndNegativeErosion() {
        var cycle=new LakeSpillV2.Result(2,1,new double[]{1,1},new int[]{1,0});
        assertThrows(IllegalArgumentException.class,()->
            SedimentBudgetV2.route(cycle,new double[]{1,1},new double[]{1,1}));
        assertThrows(IllegalArgumentException.class,()->
            SedimentBudgetV2.erodedMass(new double[]{1},new double[]{2},
                                        new double[]{1},2000));
    }

    @Test void erosionOverlayFeedsConservativeSedimentTransport() {
        double[] terrain={0,0,0,0,10,0,0,0,0};
        double[] rain={1,1,1,1,1,1,1,1,1};
        double[] area={1,1,1,1,1,1,1,1,1};
        var lakes=LakeSpillV2.fill(3,3,terrain);
        var discharge=SpillDischargeV2.accumulate(lakes,rain);
        var eroded=RiverErosionV2.erode(terrain,lakes,discharge,0.1,2);
        var mass=SedimentBudgetV2.erodedMass(terrain,eroded,area,2000);
        assertTrue(mass[4]>0);
        var sediment=SedimentBudgetV2.route(lakes,mass,
            new double[]{10000,10000,10000,10000,10000,10000,10000,10000,10000});
        double erodedTotal=0,accounted=0;
        for(double value:mass)erodedTotal+=value;
        for(double value:sediment.deposited())accounted+=value;
        for(double value:sediment.outletExport())accounted+=value;
        assertEquals(erodedTotal,accounted,1e-8);
    }
}
