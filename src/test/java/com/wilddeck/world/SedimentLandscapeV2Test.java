package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SedimentLandscapeV2Test {
    private static double[] terrain(){
        var bed=new double[25];
        for(int y=0;y<5;y++)for(int x=0;x<5;x++)
            bed[y*5+x]=20-x-y;
        return bed;
    }

    @Test void depositionAndExportConserveMassAndRainfall(){
        double[] original=terrain(),rain=new double[25],area=new double[25],
                 capacity=new double[25];
        Arrays.fill(rain,2);
        Arrays.fill(area,1);
        Arrays.fill(capacity,2);
        var result=SedimentLandscapeV2.advanceToVerifiedOutlets(
            5,5,original,rain,area,2000,.1,.4,capacity,Set.of(24));
        assertTrue(result.erodedMass()>0);
        assertTrue(result.depositedMass()>0);
        assertTrue(result.exportedMass()>=0);
        assertEquals(result.erodedMass(),
            result.depositedMass()+result.exportedMass(),1e-7);
        assertEquals(50,result.discharge().flow()[24],1e-8);
        assertEquals(-1,result.drainage().spillTo()[24]);
        assertArrayEquals(terrain(),original,0);
        assertTrue(Arrays.stream(result.depositionHeight()).anyMatch(h->h>0));
        var changed=result.terrain();
        changed[0]=-100;
        assertNotEquals(-100,result.terrain()[0]);
        var again=SedimentLandscapeV2.advanceToVerifiedOutlets(
            5,5,original,rain,area,2000,.1,.4,capacity,List.of(24));
        assertArrayEquals(result.terrain(),again.terrain(),0);
    }

    @Test void zeroTransportCapacityReturnsAllIncisedMassToBed(){
        double[] original=terrain(),rain=new double[25],area=new double[25];
        Arrays.fill(rain,1);
        Arrays.fill(area,1);
        var result=SedimentLandscapeV2.advanceToVerifiedOutlets(
            5,5,original,rain,area,1,.1,.5,new double[25],Set.of(24));
        assertTrue(result.erodedMass()>0);
        assertEquals(result.erodedMass(),result.depositedMass(),1e-8);
        assertEquals(0,result.exportedMass(),1e-8);
        assertArrayEquals(original,result.terrain(),1e-9);
    }

    @Test void rejectsInvalidGeometryAndCapacity(){
        double[] original=terrain(),rain=new double[25],area=new double[25];
        Arrays.fill(area,1);
        var capacity=new double[25];
        capacity[3]=-1;
        assertThrows(IllegalArgumentException.class,()->
            SedimentLandscapeV2.advanceToVerifiedOutlets(
                5,5,original,rain,area,2000,.1,.4,capacity,Set.of(24)));
        assertThrows(IllegalArgumentException.class,()->
            SedimentLandscapeV2.advanceToVerifiedOutlets(
                5,5,original,rain,area,0,.1,.4,new double[25],Set.of(24)));
        assertThrows(IllegalArgumentException.class,()->
            SedimentLandscapeV2.advanceToVerifiedOutlets(
                5,5,original,rain,area,2000,.1,.4,new double[25],Set.of()));
    }
}
