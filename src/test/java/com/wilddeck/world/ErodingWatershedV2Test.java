package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ErodingWatershedV2Test {
    private static double outlets(ErodingWatershedV2.Step step){
        var next=step.lakes().spillTo();
        var flow=step.discharge().flow();
        double total=0;
        for(int i=0;i<next.length;i++)if(next[i]<0)total+=flow[i];
        return total;
    }

    @Test void reroutesAfterBoundedIncisionWithoutCreatingOrLosingRain(){
        int width=5,height=5;
        var elevation=new double[25];
        var rainfall=new double[25];
        Arrays.fill(rainfall,2);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)
            elevation[y*width+x]=20-x-y;
        var step=ErodingWatershedV2.advance(width,height,elevation,rainfall,.1,.5);
        assertEquals(50,outlets(step),1e-8);
        boolean cut=false;
        for(int i=0;i<elevation.length;i++){
            assertTrue(step.elevation()[i]<=elevation[i]+1e-10);
            assertTrue(step.incision()[i]>=0&&step.incision()[i]<=.5+1e-10);
            if(step.incision()[i]>0)cut=true;
        }
        assertTrue(cut);
        assertEquals(20,elevation[0],0); // source terrain untouched
        var mutated=step.elevation();
        mutated[0]=-100;
        assertNotEquals(-100,step.elevation()[0]);
        var repeat=ErodingWatershedV2.advance(width,height,elevation,rainfall,.1,.5);
        assertArrayEquals(step.elevation(),repeat.elevation(),0);
    }

    @Test void zeroStrengthLeavesTerrainUnchangedAndInvalidRainFails(){
        var elevation=new double[]{3,2,2,1};
        var rain=new double[]{1,1,1,1};
        var step=ErodingWatershedV2.advance(2,2,elevation,rain,0,1);
        assertArrayEquals(elevation,step.elevation(),0);
        assertEquals(4,outlets(step),1e-9);
        assertThrows(IllegalArgumentException.class,()->
            ErodingWatershedV2.advance(2,2,elevation,new double[]{1,-1,1,1},.1,1));
    }
}
