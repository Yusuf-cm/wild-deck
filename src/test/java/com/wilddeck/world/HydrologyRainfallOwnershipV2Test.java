package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRainfallOwnershipV2Test {
    @Test void sharedVerticalEdgeIsOwnedByLeftTile(){
        double[] rain=new double[9];Arrays.fill(rain,1);
        var left=HydrologyRainfallOwnershipV2.ownedRainfall(rain,3,3,2,0,0);
        var right=HydrologyRainfallOwnershipV2.ownedRainfall(rain,3,3,2,1,0);
        for(int y=0;y<3;y++){
            assertEquals(1,left[y*3+2]);
            assertEquals(0,right[y*3]);
        }
        assertEquals(15,Arrays.stream(left).sum()+Arrays.stream(right).sum());
    }
    @Test void sharedCornerIsOwnedOnce(){
        double[] rain=new double[9];Arrays.fill(rain,1);
        double total=0;
        for(int row=0;row<2;row++)for(int col=0;col<2;col++)
            total+=Arrays.stream(HydrologyRainfallOwnershipV2.ownedRainfall(rain,3,3,2,col,row)).sum();
        assertEquals(25,total); // Unique 5x5 samples.
    }
    @Test void rejectsInvalidTiling(){
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRainfallOwnershipV2.ownedRainfall(new double[9],3,3,3,0,0));
    }
}
