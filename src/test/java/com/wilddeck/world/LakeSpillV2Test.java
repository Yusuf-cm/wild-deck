package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
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
        assertEquals(1,result.waterSurface()[4]);
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
    @Test void onlyVerifiedOutletDrainsEnclosedDepressionAndLowWindowEdge(){
        double[] terrain={0,8,8,8,0,8,8,8,5};
        var result=LakeSpillV2.fillToVerifiedOutlets(3,3,terrain,Set.of(8));
        assertEquals(5,result.waterSurface()[0],0);
        assertEquals(5,result.waterSurface()[4],0);
        assertEquals(-1,result.spillTo()[8]);
        assertNotEquals(-1,result.spillTo()[0]); // edge is NOT an invented outlet
        assertNotEquals(-1,result.spillTo()[4]);
        var flow=SpillDischargeV2.accumulate(result,new double[]{1,1,1,1,1,1,1,1,1});
        assertEquals(9,flow.flow()[8],1e-9);
        for(int start=0;start<9;start++){
            int cursor=start,steps=0;
            while(result.spillTo()[cursor]>=0){
                cursor=result.spillTo()[cursor];
                assertTrue(++steps<=9,"Acyclic route");
            }
            assertEquals(8,cursor);
        }
    }

    @Test void verifiedMultiOutletFlatRoutesAreDeterministic(){
        var terrain=new double[16];
        var a=LakeSpillV2.fillToVerifiedOutlets(4,4,terrain,List.of(15,0));
        var b=LakeSpillV2.fillToVerifiedOutlets(4,4,terrain,List.of(0,15));
        assertArrayEquals(a.waterSurface(),b.waterSurface(),0);
        assertArrayEquals(a.spillTo(),b.spillTo());
        for(int start=0;start<16;start++){
            int cursor=start,steps=0;
            while(a.spillTo()[cursor]>=0){
                cursor=a.spillTo()[cursor];
                assertTrue(++steps<=16);
            }
            assertTrue(cursor==0||cursor==15);
        }
    }

    @Test void rejectsUnverifiedOrInvalidOutletSets(){
        assertThrows(IllegalArgumentException.class,()->
            LakeSpillV2.fillToVerifiedOutlets(2,2,new double[4],Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            LakeSpillV2.fillToVerifiedOutlets(2,2,new double[4],Set.of(4)));
        assertThrows(IllegalArgumentException.class,()->
            LakeSpillV2.fillToVerifiedOutlets(2,2,new double[]{0,1,Double.NaN,1},Set.of(0)));
    }

    @Test void invalidElevationRejected(){
        assertThrows(IllegalArgumentException.class,()->LakeSpillV2.fill(2,2,new double[3]));
        assertThrows(IllegalArgumentException.class,()->LakeSpillV2.fill(1,1,new double[]{Double.NaN}));
    }
}
