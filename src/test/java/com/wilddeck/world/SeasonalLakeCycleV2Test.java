package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalLakeCycleV2Test {
    private static LakeReservoirV2.Basin basin(){
        return new LakeReservoirV2.Basin(new double[]{0,1},
                                         new double[]{10,10},2);
    }

    @Test void wetAndDrySeasonsConserveStorageEvaporationAndSpill(){
        var periods=List.of(
            new SeasonalLakeCycleV2.Period(1,0.2,0,0.5),
            new SeasonalLakeCycleV2.Period(2,0.1,5,0.5),
            new SeasonalLakeCycleV2.Period(0,0.5,0,0));
        var result=SeasonalLakeCycleV2.simulate(basin(),10,20,periods);
        assertEquals(3,result.seasons().size());
        assertEquals(35,result.totalInput(),1e-9);
        assertEquals(14,result.totalEvaporation(),1e-9);
        assertEquals(11,result.totalOverflow(),1e-9);
        assertEquals(20,result.finalVolume(),1e-9);
        assertEquals(result.initialVolume()+result.totalInput(),
            result.finalVolume()+result.totalEvaporation()+result.totalOverflow(),1e-9);
        assertEquals(30,result.seasons().get(1).lake().volume(),1e-9);
        assertEquals(11,result.seasons().get(1).lake().overflow(),1e-9);
        assertEquals(result,SeasonalLakeCycleV2.simulate(basin(),10,20,periods));
    }

    @Test void emptySeasonLeavesInitialVolumeUntouched(){
        var result=SeasonalLakeCycleV2.simulate(basin(),5,20,List.of());
        assertEquals(5,result.finalVolume());
        assertTrue(result.seasons().isEmpty());
        assertEquals(0,result.totalInput());
    }

    @Test void rejectsInvalidRunoffAndOverflow(){
        assertThrows(IllegalArgumentException.class,()->
            new SeasonalLakeCycleV2.Period(1,0,0,1.1));
        assertThrows(IllegalArgumentException.class,()->
            SeasonalLakeCycleV2.simulate(basin(),31,20,List.of()));
        assertThrows(IllegalArgumentException.class,()->
            SeasonalLakeCycleV2.simulate(basin(),1,Double.MAX_VALUE,
                List.of(new SeasonalLakeCycleV2.Period(2,0,0,1))));
    }
    @Test void substepsAccountForEvaporationWhilePreviouslyDryLakeFills(){
        var shallow=new LakeReservoirV2.Basin(new double[]{0},new double[]{10},10);
        var periods=List.of(new SeasonalLakeCycleV2.Period(1,1,0,1));
        var coarse=SeasonalLakeCycleV2.simulate(shallow,0,10,periods);
        var refined=SeasonalLakeCycleV2.simulateSubsteps(shallow,0,10,periods,10);
        assertEquals(coarse,SeasonalLakeCycleV2.simulateSubsteps(shallow,0,10,periods,1));
        assertEquals(10,coarse.finalVolume(),1e-9);
        assertEquals(1,refined.finalVolume(),1e-9);
        assertEquals(9,refined.totalEvaporation(),1e-9);
        assertEquals(0,refined.totalOverflow(),1e-9);
        assertEquals(10,refined.totalInput(),1e-9);
        assertEquals(refined.initialVolume()+refined.totalInput(),
            refined.finalVolume()+refined.totalEvaporation()+refined.totalOverflow(),1e-8);
        assertEquals(1,refined.seasons().size());
        assertEquals(9,refined.seasons().get(0).lake().evaporated(),1e-9);
    }

    @Test void rejectsPotentialEvaporationOverflowInsteadOfSilentlyDrainingLake(){
        var huge=new LakeReservoirV2.Basin(
            new double[]{0},new double[]{Double.MAX_VALUE},1);
        assertThrows(IllegalArgumentException.class,()->
            LakeReservoirV2.step(huge,Double.MAX_VALUE,0,2));
    }

    @Test void substepBudgetIsBounded(){
        assertThrows(IllegalArgumentException.class,()->
            SeasonalLakeCycleV2.simulateSubsteps(basin(),0,1,List.of(),0));
        assertThrows(IllegalArgumentException.class,()->
            SeasonalLakeCycleV2.simulateSubsteps(basin(),0,1,List.of(),4097));
    }


    @Test void irregularSubstepsAndWetDryCyclesConserveLakeWater() {
        var small = new LakeReservoirV2.Basin(
            new double[]{0}, new double[]{3}, 2);
        var periods = List.of(
            new SeasonalLakeCycleV2.Period(0.7, 0.03, 0.2, 0.75),
            new SeasonalLakeCycleV2.Period(5, 0.1, 1, 0.5),
            new SeasonalLakeCycleV2.Period(0, 0.4, 0, 0));
        var result = SeasonalLakeCycleV2.simulateSubsteps(
            small, 0, 3, periods, 4093);
        double previous = result.initialVolume();
        for (int i = 0; i < periods.size(); i++) {
            var period = periods.get(i);
            var season = result.seasons().get(i);
            double supplied = season.runoffInflow() + period.upstreamInflow();
            assertEquals(previous + supplied,
                season.lake().volume() + season.lake().evaporated()
                    + season.lake().overflow(), 1e-7);
            assertTrue(season.lake().volume() >= 0);
            assertTrue(season.lake().volume() <= small.capacity());
            previous = season.lake().volume();
        }
        assertEquals(result.initialVolume() + result.totalInput(),
            result.finalVolume() + result.totalEvaporation()
                + result.totalOverflow(), 1e-7);
        assertEquals(result, SeasonalLakeCycleV2.simulateSubsteps(
            small, 0, 3, periods, 4093));
    }

}
