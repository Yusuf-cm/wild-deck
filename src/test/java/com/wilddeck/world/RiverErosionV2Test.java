package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverErosionV2Test {
    @Test void boundedDeterministicAndNeverRaisesTerrain(){
        double[] terrain={10,10,10,10,4,10,10,10,10};
        var lakes=LakeSpillV2.fill(3,3,terrain);
        var flow=SpillDischargeV2.accumulate(lakes,new double[]{1,1,1,1,5,1,1,1,1});
        var first=RiverErosionV2.erode(terrain,lakes,flow,0.5,2);
        var second=RiverErosionV2.erode(terrain,lakes,flow,0.5,2);
        assertArrayEquals(first,second);
        for(int i=0;i<terrain.length;i++){
            assertTrue(first[i]<=terrain[i]);
            assertTrue(first[i]>=terrain[i]-2);
        }
    }
    @Test void zeroStrengthPreservesTerrain(){
        double[] terrain={2,3,4,5};
        var lakes=LakeSpillV2.fill(2,2,terrain);
        var flow=SpillDischargeV2.accumulate(lakes,new double[]{1,1,1,1});
        assertArrayEquals(terrain,RiverErosionV2.erode(terrain,lakes,flow,0,10));
    }
    @Test void neverCutsBelowDownstreamBedAndRejectsOverflow() {
        int width=5,height=5;
        double[] terrain=new double[25],rain=new double[25];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            terrain[y*width+x]=20-x-y;
            rain[y*width+x]=1;
        }
        var lakes=LakeSpillV2.fill(width,height,terrain);
        var flow=SpillDischargeV2.accumulate(lakes,rain);
        var eroded=RiverErosionV2.erode(terrain,lakes,flow,100,100);
        int[] downstream=lakes.spillTo();
        boolean cut=false;
        for(int i=0;i<eroded.length;i++)if(downstream[i]>=0){
            assertTrue(eroded[i]>=Math.min(terrain[i],terrain[downstream[i]])-1e-10);
            if(eroded[i]<terrain[i])cut=true;
        }
        assertTrue(cut);
        assertThrows(IllegalArgumentException.class,()->
            RiverErosionV2.erode(terrain,lakes,flow,Double.MAX_VALUE,100));
    }

}
