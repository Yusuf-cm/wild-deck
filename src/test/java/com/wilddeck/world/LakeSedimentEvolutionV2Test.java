package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LakeSedimentEvolutionV2Test {
    private static LakeReservoirV2.Basin basin() {
        return new LakeReservoirV2.Basin(new double[]{0, 1},
                                         new double[]{1, 1}, 3);
    }

    @Test void bottomUpDepositionChangesHypsometryAndDisplacesWater() {
        var original = basin();
        var result = LakeSedimentEvolutionV2.deposit(original, 4, 4000, 2000);
        assertArrayEquals(new double[]{1.5, 1.5}, result.bedElevation(), 1e-12);
        assertEquals(3, result.basin().orElseThrow().capacity(), 1e-12);
        assertEquals(3, result.storedWater(), 1e-12);
        assertEquals(1, result.displacedWater(), 1e-12);
        assertEquals(4000, result.depositedSediment(), 1e-9);
        assertEquals(0, result.exportedSediment(), 1e-9);
        assertEquals(4, result.storedWater() + result.displacedWater(), 1e-12);
        assertEquals(5, original.capacity(), 1e-12);
    }

    @Test void sedimentLevelsDeepCellsBeforeShallowCells() {
        var original = new LakeReservoirV2.Basin(new double[]{0, 1, 2},
                                                 new double[]{2, 1, 1}, 3);
        var result = LakeSedimentEvolutionV2.deposit(original, 0, 4000, 2000);
        assertArrayEquals(new double[]{1, 1, 2}, result.bedElevation(), 1e-12);
        assertEquals(4000, result.depositedSediment(), 1e-9);
    }

    @Test void lakeCanSiltUpCompletelyWithoutInventingRemainingStorage() {
        var result = LakeSedimentEvolutionV2.deposit(basin(), 4, 10000, 2000);
        assertTrue(result.basin().isEmpty());
        assertArrayEquals(new double[]{3, 3}, result.bedElevation(), 1e-12);
        assertEquals(0, result.storedWater(), 1e-12);
        assertEquals(4, result.displacedWater(), 1e-12);
        assertEquals(10000, result.depositedSediment(), 1e-9);
    }

    @Test void sedimentBeyondSpillCapacityIsExplicitlyExported() {
        var result = LakeSedimentEvolutionV2.deposit(basin(), 1, 12000, 2000);
        assertTrue(result.basin().isEmpty());
        assertEquals(10000, result.depositedSediment(), 1e-9);
        assertEquals(2000, result.exportedSediment(), 1e-9);
        assertEquals(1, result.displacedWater(), 1e-12);
        assertEquals(12000, result.depositedSediment() + result.exportedSediment(), 1e-9);
    }

    @Test void noSedimentPreservesBasinAndWater() {
        var result = LakeSedimentEvolutionV2.deposit(basin(), 2, 0, 2000);
        assertArrayEquals(new double[]{0, 1}, result.bedElevation(), 1e-12);
        assertEquals(5, result.basin().orElseThrow().capacity(), 1e-12);
        assertEquals(2, result.storedWater(), 1e-12);
        assertEquals(0, result.displacedWater(), 1e-12);
    }

    @Test void resultGeometryIsDefensivelyCopied() {
        var result = LakeSedimentEvolutionV2.deposit(basin(), 0, 4000, 2000);
        var exposed = result.bedElevation();
        exposed[0] = 999;
        assertEquals(1.5, result.bedElevation()[0], 1e-12);
        assertEquals(3, result.basin().orElseThrow().capacity(), 1e-12);
    }

    @Test void rejectsInvalidSedimentAndWaterInputs() {
        assertThrows(IllegalArgumentException.class, () ->
            LakeSedimentEvolutionV2.deposit(basin(), 6, 1, 2000));
        assertThrows(IllegalArgumentException.class, () ->
            LakeSedimentEvolutionV2.deposit(basin(), 1, -1, 2000));
        assertThrows(IllegalArgumentException.class, () ->
            LakeSedimentEvolutionV2.deposit(basin(), 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () ->
            LakeSedimentEvolutionV2.deposit(basin(), 1, Double.NaN, 2000));
        assertThrows(IllegalArgumentException.class, () ->
            LakeSedimentEvolutionV2.deposit(basin(), 1, 1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () ->
            LakeSedimentEvolutionV2.deposit(
                new LakeReservoirV2.Basin(new double[]{0}, new double[]{1}, 1e-300),
                0, 1, Double.MIN_VALUE));
    }

    @Test void seededGeometrySweepPreservesBothConservationLaws() {
        var random = new java.util.Random(271828);
        for (int run = 0; run < 500; run++) {
            int count = 1 + random.nextInt(20);
            double[] beds = new double[count], areas = new double[count];
            double spill = 1 + random.nextDouble() * 50;
            for (int i = 0; i < count; i++) {
                beds[i] = random.nextDouble() * (spill + 5) - 2;
                areas[i] = 0.1 + random.nextDouble() * 20;
            }
            beds[0] = 0;
            var basin = new LakeReservoirV2.Basin(beds, areas, spill);
            double capacity = basin.capacity();
            double previousWater = capacity * random.nextDouble();
            double sedimentMass = capacity * 2000 * random.nextDouble() * 1.5;
            var result = LakeSedimentEvolutionV2.deposit(
                basin, previousWater, sedimentMass, 2000);
            double nextCapacity = result.basin()
                .map(LakeReservoirV2.Basin::capacity).orElse(0.0);
            double waterTolerance = 1e-8 * Math.max(1, previousWater);
            double sedimentTolerance = 1e-8 * Math.max(1, sedimentMass);
            assertEquals(previousWater,
                result.storedWater() + result.displacedWater(), waterTolerance);
            assertEquals(sedimentMass,
                result.depositedSediment() + result.exportedSediment(), sedimentTolerance);
            assertTrue(nextCapacity <= capacity + waterTolerance);
            for (int i = 0; i < count; i++)
                assertTrue(result.bedElevation()[i] >= beds[i]);
        }
    }
}
