package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class LakeFilledSurfaceEvaporationV2Test {
    private static LakeReservoirV2.Basin basin() {
        return new LakeReservoirV2.Basin(new double[]{0, 1},
                                         new double[]{1, 1}, 3);
    }

    @Test void initiallyDryLakeEvaporatesAfterReceivingWater() {
        var step = LakeReservoirV2.stepInflowThenEvaporation(basin(), 0, 2, 1);
        assertEquals(0.5, step.volume(), 1e-12);
        assertEquals(0.5, step.stage(), 1e-12);
        assertEquals(1.5, step.evaporated(), 1e-12);
        assertEquals(0, step.overflow(), 1e-12);
    }

    @Test void inflowFirstOverflowIsNotMistakenForEvaporation() {
        var step = LakeReservoirV2.stepInflowThenEvaporation(basin(), 5, 2, 1);
        assertEquals(3, step.volume(), 1e-12);
        assertEquals(2, step.evaporated(), 1e-12);
        assertEquals(2, step.overflow(), 1e-12);
        assertEquals(7, step.volume()+step.evaporated()+step.overflow(), 1e-12);
    }

    @Test void noEvaporationPreservesWaterAndExactSpill() {
        var step = LakeReservoirV2.stepInflowThenEvaporation(basin(), 3, 1, 0);
        assertEquals(4, step.volume(), 1e-12);
        assertEquals(0, step.evaporated(), 1e-12);
        assertEquals(0, step.overflow(), 1e-12);
    }

    @Test void extremeEvaporationCannotRemoveMoreThanAvailable() {
        var step = LakeReservoirV2.stepInflowThenEvaporation(basin(), 0, 2, 1e300);
        assertEquals(0, step.volume(), 1e-12);
        assertEquals(2, step.evaporated(), 1e-12);
        assertEquals(0, step.overflow(), 1e-12);
    }

    @Test void rejectsInvalidForcingAndOverflow() {
        assertThrows(IllegalArgumentException.class, () ->
            LakeReservoirV2.stepInflowThenEvaporation(basin(), 6, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
            LakeReservoirV2.stepInflowThenEvaporation(basin(), 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () ->
            LakeReservoirV2.stepInflowThenEvaporation(basin(), 0, 1, -1));
        assertThrows(IllegalArgumentException.class, () ->
            LakeReservoirV2.stepInflowThenEvaporation(\n                new LakeReservoirV2.Basin(new double[]{0}, new double[]{1}, 1e308),\n                1e308, 1e308, 1));
    }

    @Test void seededHypsometryConservesInflowAndNonnegativeFlux() {
        var random = new Random(18017);
        for (int trial=0; trial<1000; trial++) {
            int n = 1+random.nextInt(15);
            double[] beds = new double[n], areas = new double[n];
            for (int i=0; i<n; i++) {
                beds[i] = random.nextDouble()*10;
                areas[i] = 0.1+random.nextDouble()*4;
            }
            beds[0] = 0;
            var lake = new LakeReservoirV2.Basin(beds, areas, 11);
            double initial = lake.capacity()*random.nextDouble();
            double inflow = random.nextDouble()*lake.capacity()*2;
            double evaporationDepth = random.nextDouble()*15;
            var result = LakeReservoirV2.stepInflowThenEvaporation(
                lake, initial, inflow, evaporationDepth);
            double tolerance = 1e-9*Math.max(1, initial+inflow);
            assertEquals(initial+inflow,
                result.volume()+result.evaporated()+result.overflow(), tolerance);
            assertTrue(result.volume()>=0 && result.volume()<=lake.capacity());
            assertTrue(result.evaporated()>=0 && result.overflow()>=0);
        }
    }
}
