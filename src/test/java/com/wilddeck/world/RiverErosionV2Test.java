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
}
