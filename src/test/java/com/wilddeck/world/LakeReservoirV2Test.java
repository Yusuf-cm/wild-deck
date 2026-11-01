package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LakeReservoirV2Test {
    private static LakeReservoirV2.Basin basin() {
        return new LakeReservoirV2.Basin(new double[]{0,1},
                                         new double[]{1,1},3);
    }
    @Test void exactPiecewiseStorageCurveAndInverseStage() {
        var b=basin();
        assertEquals(5,b.capacity(),1e-12);
        assertEquals(0,b.stageAt(0),1e-12);
        assertEquals(1,b.stageAt(1),1e-9);
        assertEquals(2,b.stageAt(3),1e-9);
        assertEquals(3,b.stageAt(5),1e-12);
        assertEquals(1,b.wettedArea(0.5),1e-12);
        assertEquals(2,b.wettedArea(2),1e-12);
    }
    @Test void waterBalanceWithEvaporationAndSpill() {
        var b=basin();
        var result=LakeReservoirV2.step(b,2,5,0.5);
        assertEquals(1,result.evaporated(),1e-12);
        assertEquals(5,result.volume(),1e-12);
        assertEquals(1,result.overflow(),1e-12);
        assertEquals(3,result.stage(),1e-12);
        assertEquals(7,result.evaporated()+result.volume()+result.overflow(),1e-12);
    }
    @Test void emptyLakeDoesNotEvaporateAndFillsBeforeOverflow() {
        var b=basin();
        var result=LakeReservoirV2.step(b,0,2,1);
        assertEquals(0,result.evaporated(),1e-12);
        assertEquals(2,result.volume(),1e-12);
        assertEquals(0,result.overflow(),1e-12);
    }
    @Test void invalidGeometryAndWaterAreRejected() {
        assertThrows(IllegalArgumentException.class,()->
            new LakeReservoirV2.Basin(new double[]{1},new double[]{1},1));
        assertThrows(IllegalArgumentException.class,()->
            new LakeReservoirV2.Basin(new double[]{0},new double[]{-1},1));
        assertThrows(IllegalArgumentException.class,()->
            LakeReservoirV2.step(basin(),6,0,0));
        assertThrows(IllegalArgumentException.class,()->
            LakeReservoirV2.step(basin(),0,-1,0));
    }
    @Test void geometryIsImmutable() {
        double[] bed={0,1},area={1,1};
        var b=new LakeReservoirV2.Basin(bed,area,3);
        bed[0]=100;area[0]=100;
        assertEquals(5,b.capacity(),1e-12);
        b.bedElevation()[0]=100;
        assertEquals(5,b.capacity(),1e-12);
    }
}
