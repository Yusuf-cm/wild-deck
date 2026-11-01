package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalWaterBalanceV2Test {
    @Test void conservesWaterAcrossRainEvaporationStorageAndRunoff(){
        var result=SeasonalWaterBalanceV2.step(new double[]{10,0},
            new double[]{2,4},new double[]{3,10},0.25);
        for(int i=0;i<2;i++){
            double available=new double[]{12,4}[i];
            assertEquals(available,
                result.remaining()[i]+result.evaporated()[i]+result.stored()[i],1e-9);
        }
        assertEquals(6,result.remaining()[0],1e-9);
        assertEquals(0,result.remaining()[1],1e-9);
    }
    @Test void rejectsNegativeInputsAndInvalidEvaporation(){
        assertThrows(IllegalArgumentException.class,()->
            SeasonalWaterBalanceV2.step(new double[]{-1},new double[]{0},new double[]{1},0));
        assertThrows(IllegalArgumentException.class,()->
            SeasonalWaterBalanceV2.step(new double[]{1},new double[]{0},new double[]{1},1.2));
    }
}
