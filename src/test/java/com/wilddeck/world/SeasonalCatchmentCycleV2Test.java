package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalCatchmentCycleV2Test {
    @Test void wetDryWetSeasonsCarryStorageAndConserveEveryCell() {
        var periods=List.of(
            new SeasonalCatchmentCycleV2.Period(
                new double[]{3,4},new double[]{3,2},0.2),
            new SeasonalCatchmentCycleV2.Period(
                new double[]{0,0},new double[]{3,2},0.5),
            new SeasonalCatchmentCycleV2.Period(
                new double[]{1,2},new double[]{1,2},0));
        var result=SeasonalCatchmentCycleV2.simulate(new double[]{2,0},periods);
        assertEquals(3,result.periods().size());
        assertArrayEquals(new double[]{2.5,2.2},result.runoff(),1e-10);
        assertArrayEquals(new double[]{2.5,1.8},result.evaporation(),1e-10);
        assertArrayEquals(new double[]{1,2},result.finalStorage(),1e-10);
        assertArrayEquals(new double[]{3,2},result.periods().get(0).stored(),1e-10);
        assertArrayEquals(new double[]{1.5,1},result.periods().get(1).stored(),1e-10);
        for(int i=0;i<2;i++) {
            double inputs=(i==0?2:0)+(i==0?4:6);
            assertEquals(inputs,result.runoff()[i]+result.evaporation()[i]+
                result.finalStorage()[i],1e-10);
        }
        var repeat=SeasonalCatchmentCycleV2.simulate(new double[]{2,0},periods);
        assertArrayEquals(result.runoff(),repeat.runoff(),0);
    }

    @Test void snapshotsAndInputArraysAreDefensive() {
        var rain=new double[]{4};
        var capacity=new double[]{2};
        var period=new SeasonalCatchmentCycleV2.Period(rain,capacity,0);
        rain[0]=99;capacity[0]=99;
        period.rainfall()[0]=99;
        var result=SeasonalCatchmentCycleV2.simulate(new double[]{0},List.of(period));
        assertArrayEquals(new double[]{2},result.runoff(),0);
        result.finalStorage()[0]=99;
        result.runoff()[0]=99;
        assertArrayEquals(new double[]{2},result.finalStorage(),0);
        assertArrayEquals(new double[]{2},result.runoff(),0);
    }

    @Test void emptyCyclePreservesInitialStorageAndRejectsChangingGrid() {
        var empty=SeasonalCatchmentCycleV2.simulate(new double[]{1,2},List.of());
        assertArrayEquals(new double[]{1,2},empty.finalStorage(),0);
        assertArrayEquals(new double[]{0,0},empty.runoff(),0);
        assertThrows(IllegalArgumentException.class,()->
            SeasonalCatchmentCycleV2.simulate(new double[]{1},List.of(
                new SeasonalCatchmentCycleV2.Period(
                    new double[]{1,2},new double[]{1,2},0))));
        assertThrows(IllegalArgumentException.class,()->
            SeasonalCatchmentCycleV2.simulate(new double[]{-1},List.of()));
    }
}
