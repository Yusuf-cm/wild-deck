package com.wilddeck.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalLakeEvaporationModeV2Test {
    private static LakeReservoirV2.Basin basin() {
        return new LakeReservoirV2.Basin(new double[]{0, 1},
                                         new double[]{1, 1}, 3);
    }

    @Test void optInModeEvaporatesWaterThatArrivesInDryLake() {
        var state = SeasonalLakeEvolutionV2.State.initial(basin(), 0);
        var forcing = new SeasonalLakeEvolutionV2.Forcing(2, 1, 0, 2000);
        var legacy = SeasonalLakeEvolutionV2.advance(state, forcing);
        var filled = SeasonalLakeEvolutionV2.advance(state, forcing,
            SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
        assertEquals(2, legacy.state().storedWater(), 1e-12);
        assertEquals(0, legacy.evaporated(), 1e-12);
        assertEquals(0.5, filled.state().storedWater(), 1e-12);
        assertEquals(1.5, filled.evaporated(), 1e-12);
        assertEquals(2, filled.evaporated()+filled.waterOverflow()+
                        filled.state().storedWater(), 1e-12);
    }

    @Test void evaporationAndSedimentDisplacementConserveBothBudgets() {
        var step = SeasonalLakeEvolutionV2.advance(
            SeasonalLakeEvolutionV2.State.initial(basin(), 4),
            new SeasonalLakeEvolutionV2.Forcing(2, 0.5, 4000, 2000),
            SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
        assertEquals(1, step.evaporated(), 1e-12);
        assertEquals(2, step.waterOverflow(), 1e-12);
        assertEquals(3, step.state().storedWater(), 1e-12);
        assertEquals(6, step.evaporated()+step.waterOverflow()+
                        step.state().storedWater(), 1e-12);
        assertEquals(4000, step.depositedSediment()+step.exportedSediment(), 1e-9);
    }

    @Test void defaultModePreservesOriginalWaterBudget() {
        var state = SeasonalLakeEvolutionV2.State.initial(basin(), 2);
        var forcing = new SeasonalLakeEvolutionV2.Forcing(5, 0.5, 4000, 2000);
        var original = SeasonalLakeEvolutionV2.advance(state, forcing);
        var explicit = SeasonalLakeEvolutionV2.advance(state, forcing,
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(original.state().storedWater(), explicit.state().storedWater(), 1e-12);
        assertEquals(original.evaporated(), explicit.evaporated(), 1e-12);
        assertEquals(original.waterOverflow(), explicit.waterOverflow(), 1e-12);
        assertArrayEquals(original.state().bedElevation(),
                          explicit.state().bedElevation(), 1e-12);
    }

    @Test void multiSeasonModeIsDeterministicAndInputsAreUnchanged() {
        var initial = SeasonalLakeEvolutionV2.State.initial(basin(), 0);
        var seasons = List.of(
            new SeasonalLakeEvolutionV2.Forcing(2, 1, 0, 2000),
            new SeasonalLakeEvolutionV2.Forcing(2, 0.3, 1500, 2000),
            new SeasonalLakeEvolutionV2.Forcing(0, 0.5, 1000, 2000));
        var first = SeasonalLakeEvolutionV2.simulate(initial, seasons,
            SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
        var second = SeasonalLakeEvolutionV2.simulate(initial, seasons,
            SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
        for (int i=0; i<first.size(); i++) {
            assertEquals(first.get(i).state().storedWater(),
                         second.get(i).state().storedWater(), 1e-12);
            assertEquals(first.get(i).evaporated(), second.get(i).evaporated(), 1e-12);
            assertArrayEquals(first.get(i).state().bedElevation(),
                              second.get(i).state().bedElevation(), 1e-12);
        }
        assertEquals(0, initial.storedWater(), 1e-12);
        assertArrayEquals(new double[]{0,1}, initial.bedElevation(), 1e-12);
    }

    @Test void invalidModesAndNonfiniteStatesFailClosed() {
        var state = SeasonalLakeEvolutionV2.State.initial(basin(), 0);
        var forcing = new SeasonalLakeEvolutionV2.Forcing(1, 1, 0, 2000);
        assertThrows(NullPointerException.class, () ->
            SeasonalLakeEvolutionV2.advance(state, forcing, null));
        assertThrows(NullPointerException.class, () ->
            SeasonalLakeEvolutionV2.simulate(state, List.of(forcing), null));
        assertThrows(IllegalArgumentException.class, () ->
            new SeasonalLakeEvolutionV2.State(Optional.empty(),
                new double[]{Double.NaN}, 0));
        assertThrows(IllegalArgumentException.class, () ->
            new SeasonalLakeEvolutionV2.State(Optional.empty(), new double[]{}, 0));
        assertThrows(IllegalArgumentException.class, () ->
            new SeasonalLakeEvolutionV2.Step(state, Double.NaN, 0, 0, 0));
    }

    @Test void seededSeasonalWaterAndSedimentConservation() {
        var random = new Random(293811);
        for (int run=0; run<200; run++) {
            double[] bed = new double[]{0, random.nextDouble()*2};
            var lake = new LakeReservoirV2.Basin(bed,
                new double[]{0.5+random.nextDouble(), 0.5+random.nextDouble()}, 3);
            var state = SeasonalLakeEvolutionV2.State.initial(
                lake, lake.capacity()*random.nextDouble());
            for (int season=0; season<8; season++) {
                double initial = state.storedWater();
                double inflow = random.nextDouble()*5;
                double sediment = random.nextDouble()*1500;
                var step = SeasonalLakeEvolutionV2.advance(state,
                    new SeasonalLakeEvolutionV2.Forcing(
                        inflow, random.nextDouble()*2, sediment, 2000),
                    SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
                assertEquals(initial+inflow, step.state().storedWater()+
                    step.evaporated()+step.waterOverflow(),
                    1e-9*Math.max(1, initial+inflow));
                assertEquals(sediment, step.depositedSediment()+
                    step.exportedSediment(), 1e-8*Math.max(1, sediment));
                state = step.state();
            }
        }
    }
}
