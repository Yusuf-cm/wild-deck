package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LakeBasinExtractorV2Test {
    @Test void separatedPitsProduceDistinctConservativeReservoirs() {
        double[] terrain=new double[25],area=new double[25];
        Arrays.fill(terrain,10);Arrays.fill(area,2);
        terrain[2*5+1]=0;terrain[2*5+3]=0;
        var spill=LakeSpillV2.fill(5,5,terrain);
        var lakes=LakeBasinExtractorV2.extract(spill,terrain,area);
        assertEquals(2,lakes.size());
        assertEquals(11,lakes.get(0).anchorIndex());
        assertEquals(13,lakes.get(1).anchorIndex());
        for(var lake:lakes){
            assertEquals(1,lake.cells().size());
            assertEquals(20,lake.reservoir().capacity(),1e-9);
            assertEquals(5,lake.reservoir().stageAt(10),1e-9);
            var step=LakeReservoirV2.step(lake.reservoir(),10,15,0);
            assertEquals(20,step.volume(),1e-9);
            assertEquals(5,step.overflow(),1e-9);
        }
    }
    @Test void connectedFloodedCellsShareOneReservoir() {
        double[] terrain=new double[25],area=new double[25];
        Arrays.fill(terrain,10);Arrays.fill(area,1);
        terrain[11]=0;terrain[12]=0;terrain[13]=0;
        var lakes=LakeBasinExtractorV2.extract(LakeSpillV2.fill(5,5,terrain),terrain,area);
        assertEquals(1,lakes.size());
        assertEquals(List.of(11,12,13),lakes.get(0).cells());
        assertEquals(30,lakes.get(0).reservoir().capacity(),1e-9);
    }
    @Test void flatDryTerrainHasNoReservoirs() {
        double[] terrain=new double[9],area=new double[9];
        Arrays.fill(terrain,10);Arrays.fill(area,1);
        assertTrue(LakeBasinExtractorV2.extract(
            LakeSpillV2.fill(3,3,terrain),terrain,area).isEmpty());
    }
    @Test void invalidAreasAreRejected() {
        double[] terrain=new double[9],area=new double[9];
        Arrays.fill(terrain,10);Arrays.fill(area,1);area[0]=0;
        assertThrows(IllegalArgumentException.class,()->
            LakeBasinExtractorV2.extract(LakeSpillV2.fill(3,3,terrain),terrain,area));
    }
}
