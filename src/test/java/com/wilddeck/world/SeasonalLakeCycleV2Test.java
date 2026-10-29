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
}
