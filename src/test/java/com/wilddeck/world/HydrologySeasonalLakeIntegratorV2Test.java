package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLakeIntegratorV2Test {
    private static final LakeReservoirV2.Basin BASIN =
        new LakeReservoirV2.Basin(new double[]{0},new double[]{1},10);

    @Test void oneStepPreservesExistingLakeSemantics() {
        var original = LakeReservoirV2.stepInflowThenEvaporation(BASIN,9,10,5);
        assertEquals(original,HydrologySeasonalLakeIntegratorV2.step(BASIN,9,10,5,1));
        assertEquals(9,original.overflow(),1e-12);
    }

    @Test void seasonalSubstepsCanEvaporateBeforeLaterRainArrives() {
        var result = HydrologySeasonalLakeIntegratorV2.step(BASIN,9,10,5,10);
        assertEquals(9.5,result.volume(),1e-12);
        assertEquals(4.5,result.overflow(),1e-12);
        assertEquals(5,result.evaporated(),1e-12);
        assertEquals(19,result.volume()+result.overflow()+result.evaporated(),1e-12);
    }

    @Test void stormTimingChangesStorageButNotConservation() {
        var early = HydrologySeasonalLakeIntegratorV2.stepSchedule(BASIN,0,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(20,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,5)));
        var late = HydrologySeasonalLakeIntegratorV2.stepSchedule(BASIN,0,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(0,5),
            new HydrologySeasonalLakeIntegratorV2.Interval(20,0)));
        assertEquals(5,early.volume(),1e-12);
        assertEquals(10,late.volume(),1e-12);
        assertEquals(20,early.volume()+early.evaporated()+early.overflow(),1e-12);
        assertEquals(20,late.volume()+late.evaporated()+late.overflow(),1e-12);
    }

    @Test void nonflatHypsometryConservesVolume() {
        var basin = new LakeReservoirV2.Basin(
            new double[]{0,2,4},new double[]{1,2,3},6);
        var result = HydrologySeasonalLakeIntegratorV2.step(basin,3,30,2,365);
        assertTrue(result.volume() >= 0 && result.volume() <= basin.capacity());
        assertEquals(33,result.volume()+result.evaporated()+result.overflow(),1e-7);
        assertEquals(basin.stageAt(result.volume()),result.stage(),1e-10);
    }

    @Test void zeroForcingPreservesExistingStorage() {
        var result = HydrologySeasonalLakeIntegratorV2.step(BASIN,4,0,0,365);
        assertEquals(4,result.volume(),1e-12);
        assertEquals(0,result.overflow(),1e-12);
        assertEquals(0,result.evaporated(),1e-12);
    }

    @Test void rejectsInvalidCountsStorageAndWeather() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeIntegratorV2.step(BASIN,0,0,0,0));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeIntegratorV2.step(BASIN,0,0,0,4097));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeIntegratorV2.step(BASIN,11,0,0,3));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeIntegratorV2.step(BASIN,0,Double.NaN,0,3));
        assertThrows(IllegalArgumentException.class,() ->
            new HydrologySeasonalLakeIntegratorV2.Interval(1,-1));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeIntegratorV2.stepSchedule(BASIN,0,List.of()));
        assertThrows(NullPointerException.class,() ->
            HydrologySeasonalLakeIntegratorV2.stepSchedule(BASIN,0,Arrays.asList(
                new HydrologySeasonalLakeIntegratorV2.Interval(1,0),null)));
    }

    @Test void randomizedWaterBudgetAndReplayAreStable() {
        var random = new Random(20261102);
        for (int i=0;i<500;i++) {
            double previous = random.nextDouble()*10;
            double inflow = random.nextDouble()*100;
            double depth = random.nextDouble()*20;
            int steps = 1+random.nextInt(365);
            var result = HydrologySeasonalLakeIntegratorV2.step(
                BASIN,previous,inflow,depth,steps);
            assertEquals(result,HydrologySeasonalLakeIntegratorV2.step(
                BASIN,previous,inflow,depth,steps));
            assertEquals(previous+inflow,
                result.volume()+result.evaporated()+result.overflow(),1e-7);
            assertTrue(result.volume() >= 0 && result.volume() <= BASIN.capacity());
        }
    }

    @Test void fourHundredSeasonsRetainGlobalLakeMass() {
        var basin = new LakeReservoirV2.Basin(
            new double[]{-4,-1,2,5},new double[]{2,3,5,7},9);
        var random = new Random(20261102);
        double storage = 0, precipitation = 0, evaporated = 0, spilled = 0;
        for (int year=0;year<100;year++) for (int season=0;season<4;season++) {
            double incoming = random.nextDouble()*80;
            double evaporation = random.nextDouble()*2;
            var result = HydrologySeasonalLakeIntegratorV2.step(
                basin,storage,incoming,evaporation,30);
            precipitation += incoming;
            evaporated += result.evaporated();
            spilled += result.overflow();
            storage = result.volume();
            assertTrue(storage >= 0 && storage <= basin.capacity());
        }
        assertEquals(precipitation,evaporated+spilled+storage,1e-6);
    }
}
