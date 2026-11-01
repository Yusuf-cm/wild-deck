package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalLakeEvolutionV2Test {
    private static LakeReservoirV2.Basin basin() {
        return new LakeReservoirV2.Basin(new double[]{0, 1},
                                         new double[]{1, 1}, 3);
    }

    @Test void seasonalEvaporationAndSedimentDisplacementConserveWaterAndMass() {
        var step = SeasonalLakeEvolutionV2.advance(
            SeasonalLakeEvolutionV2.State.initial(basin(), 2),
            new SeasonalLakeEvolutionV2.Forcing(5, 0.5, 4000, 2000));
        assertEquals(1, step.evaporated(), 1e-12);
        assertEquals(3, step.waterOverflow(), 1e-12);
        assertEquals(3, step.state().storedWater(), 1e-12);
        assertEquals(7, step.evaporated() + step.waterOverflow() +
                        step.state().storedWater(), 1e-12);
        assertEquals(4000, step.depositedSediment() + step.exportedSediment(), 1e-9);
        assertEquals(3, step.state().basin().orElseThrow().capacity(), 1e-12);
    }

    @Test void completeSiltingPermanentlyStopsLakeStorageInLaterSeasons() {
        var steps = SeasonalLakeEvolutionV2.simulate(
            SeasonalLakeEvolutionV2.State.initial(basin(), 2),
            List.of(new SeasonalLakeEvolutionV2.Forcing(0, 0, 10000, 2000),
                    new SeasonalLakeEvolutionV2.Forcing(7, 1, 3000, 2000)));
        assertTrue(steps.get(0).state().basin().isEmpty());
        assertEquals(2, steps.get(0).waterOverflow(), 1e-12);
        assertEquals(7, steps.get(1).waterOverflow(), 1e-12);
        assertEquals(0, steps.get(1).evaporated(), 1e-12);
        assertEquals(3000, steps.get(1).exportedSediment(), 1e-9);
        assertEquals(0, steps.get(1).state().storedWater(), 1e-12);
    }

    @Test void seasonalSequenceIsDeterministicAndDoesNotMutateInput() {
        var initial = SeasonalLakeEvolutionV2.State.initial(basin(), 2);
        var seasons = List.of(new SeasonalLakeEvolutionV2.Forcing(1, 0.2, 500, 2000),
                              new SeasonalLakeEvolutionV2.Forcing(0, 0.1, 700, 2000),
                              new SeasonalLakeEvolutionV2.Forcing(3, 0, 0, 2000));
        var first = SeasonalLakeEvolutionV2.simulate(initial, seasons);
        var second = SeasonalLakeEvolutionV2.simulate(initial, seasons);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).state().storedWater(),
                         second.get(i).state().storedWater(), 1e-12);
            assertArrayEquals(first.get(i).state().bedElevation(),
                              second.get(i).state().bedElevation(), 1e-12);
            assertEquals(first.get(i).waterOverflow(), second.get(i).waterOverflow(), 1e-12);
        }
        assertEquals(2, initial.storedWater(), 1e-12);
        assertArrayEquals(new double[]{0, 1}, initial.bedElevation(), 1e-12);
        var exposed = first.get(0).state().bedElevation();
        exposed[0] = 99;
        assertNotEquals(99, first.get(0).state().bedElevation()[0]);
    }

    @Test void emptyLakeReceivesWaterBeforeEvaporationUnderExplicitApproximation() {
        var step = SeasonalLakeEvolutionV2.advance(
            SeasonalLakeEvolutionV2.State.initial(basin(), 0),
            new SeasonalLakeEvolutionV2.Forcing(2, 1, 0, 2000));
        assertEquals(0, step.evaporated(), 1e-12);
        assertEquals(2, step.state().storedWater(), 1e-12);
        assertEquals(0, step.waterOverflow(), 1e-12);
    }

    @Test void rejectsInvalidForcingAndInconsistentState() {
        assertThrows(IllegalArgumentException.class, () ->
            new SeasonalLakeEvolutionV2.Forcing(-1, 0, 0, 2000));
        assertThrows(IllegalArgumentException.class, () ->
            new SeasonalLakeEvolutionV2.Forcing(1, -1, 0, 2000));
        assertThrows(IllegalArgumentException.class, () ->
            SeasonalLakeEvolutionV2.State.initial(basin(), 6));
        assertThrows(IllegalArgumentException.class, () ->
            new SeasonalLakeEvolutionV2.State(Optional.empty(), new double[]{3}, 1));
        assertThrows(IllegalArgumentException.class, () ->
            new SeasonalLakeEvolutionV2.State(Optional.of(basin()), new double[]{9, 1}, 1));
    }
}
