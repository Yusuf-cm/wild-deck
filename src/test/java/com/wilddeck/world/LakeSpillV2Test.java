package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LakeSpillV2Test {
    @Test void enclosedDepressionFillsToRim(){
        var elevation=new double[]{5,5,5,5,0,5,5,5,5};
        var result=LakeSpillV2.fill(3,3,elevation);
        assertEquals(5,result.waterSurface()[4]);
        assertEquals(5,result.waterDepth(1,1,elevation));
        assertEquals(0,result.waterDepth(0,0,elevation));
        assertTrue(result.spillTo()[4]>=0);
    }
    @Test void boundaryOutletIsNeverArtificiallyFilled(){
        var elevation=new double[]{0,5,5,5,1,5,5,5,5};
        var result=LakeSpillV2.fill(3,3,elevation);
        assertEquals(0,result.waterSurface()[0]);
        assertEquals(5,result.waterSurface()[4]);
    }
    @Test void flatTerrainHasNoWaterDepth(){
        var elevation=new double[25];
        var result=LakeSpillV2.fill(5,5,elevation);
        for(int y=0;y<5;y++)for(int x=0;x<5;x++)
            assertEquals(0,result.waterDepth(x,y,elevation));
    }
    @Test void spillChainsTerminateAtBoundary(){
        var result=LakeSpillV2.fill(4,4,new double[]{
            4,4,4,4,4,1,2,4,4,0,1,4,4,4,4,4});
        for(int start=0;start<16;start++){
            int index=start,steps=0;
            while(index>=0){index=result.spillTo()[index];assertTrue(++steps<=16);}
        }
    }
    @Test void invalidElevationRejected(){
        assertThrows(IllegalArgumentException.class,()->LakeSpillV2.fill(2,2,new double[3]));
        assertThrows(IllegalArgumentException.class,()->LakeSpillV2.fill(1,1,new double[]{Double.NaN}));
    }
}
