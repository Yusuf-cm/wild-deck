package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpillDischargeV2Test {
    @Test void rainfallInEnclosedLakeReachesBoundaryOutlet(){
        double[] terrain={5,5,5,5,0,5,5,5,5};
        var lakes=LakeSpillV2.fill(3,3,terrain);
        var discharge=SpillDischargeV2.accumulate(lakes,new double[]{0,0,0,0,3,0,0,0,0});
        int outlet=discharge.outlet()[4];
        assertNotEquals(4,outlet);
        assertEquals(-1,lakes.spillTo()[outlet]);
        assertEquals(3,discharge.flow()[outlet]);
        assertEquals(3,discharge.flow()[4]);
    }
    @Test void conservesRainfallAcrossAllBoundaryOutlets(){
        double[] terrain={4,4,4,4,4,1,2,4,4,0,1,4,4,4,4,4};
        var lakes=LakeSpillV2.fill(4,4,terrain);
        double[] rain=new double[16];java.util.Arrays.fill(rain,2);
        var discharge=SpillDischargeV2.accumulate(lakes,rain);
        double total=0;
        for(int i=0;i<16;i++)if(lakes.spillTo()[i]<0)total+=discharge.flow()[i];
        assertEquals(32,total,1e-9);
    }
    @Test void deterministicAndRejectsBadRain(){
        var lakes=LakeSpillV2.fill(3,3,new double[]{5,5,5,5,0,5,5,5,5});
        double[] rain={1,1,1,1,1,1,1,1,1};
        var a=SpillDischargeV2.accumulate(lakes,rain);
        var b=SpillDischargeV2.accumulate(lakes,rain);
        assertArrayEquals(a.flow(),b.flow());
        assertArrayEquals(a.outlet(),b.outlet());
        rain[4]=-1;
        assertThrows(IllegalArgumentException.class,()->SpillDischargeV2.accumulate(lakes,rain));
    }
}
