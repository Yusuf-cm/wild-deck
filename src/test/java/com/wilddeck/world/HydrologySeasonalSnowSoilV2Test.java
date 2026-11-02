package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalSnowSoilV2Test {
    private static HydrologySeasonalSnowSoilV2.Forcing forcing(
            double rainDepth,double temperature,double evaporationDepth,
            double capacity) {
        return new HydrologySeasonalSnowSoilV2.Forcing(
            rainDepth,temperature,evaporationDepth,100,2,0.01,capacity);
    }

    @Test void freezingPrecipitationBecomesSnowWithoutInventingRunoff() {
        var step = HydrologySeasonalSnowSoilV2.advance(
            new HydrologySeasonalSnowSoilV2.State(0,5),
            forcing(0.2,-3,0.03,12));
        assertEquals(20,step.snowfallM3(),1e-10);
        assertEquals(0,step.rainM3(),1e-10);
        assertEquals(20,step.state().snowM3(),1e-10);
        assertEquals(2,step.state().soilM3(),1e-10);
        assertEquals(3,step.evaporatedM3(),1e-10);
        assertEquals(0,step.runoffM3(),1e-10);
        assertEquals(0,step.conservationResidualM3(),1e-9);
    }

    @Test void thawMeltIsLimitedBySnowAndSoilCapacity() {
        var step = HydrologySeasonalSnowSoilV2.advance(
            new HydrologySeasonalSnowSoilV2.State(20,2),
            forcing(0.1,5,0.02,12));
        assertEquals(10,step.rainM3(),1e-10);
        assertEquals(10,step.snowmeltM3(),1e-10);
        assertEquals(10,step.state().snowM3(),1e-10);
        assertEquals(12,step.state().soilM3(),1e-10);
        assertEquals(2,step.evaporatedM3(),1e-10);
        assertEquals(8,step.runoffM3(),1e-10);
        assertEquals(0,step.conservationResidualM3(),1e-9);
    }

    @Test void evapotranspirationCannotRemoveMoreWaterThanAvailable() {
        var step = HydrologySeasonalSnowSoilV2.advance(
            new HydrologySeasonalSnowSoilV2.State(0,1),
            forcing(0,20,10,12));
        assertEquals(1,step.evaporatedM3(),1e-10);
        assertEquals(0,step.runoffM3(),1e-10);
        assertEquals(0,step.state().soilM3(),1e-10);
    }

    @Test void warmSnowpackMeltCannotExceedStoredSnow() {
        var step = HydrologySeasonalSnowSoilV2.advance(
            new HydrologySeasonalSnowSoilV2.State(3,0),
            forcing(0,20,0,1));
        assertEquals(3,step.snowmeltM3(),1e-10);
        assertEquals(0,step.state().snowM3(),1e-10);
        assertEquals(1,step.state().soilM3(),1e-10);
        assertEquals(2,step.runoffM3(),1e-10);
    }

    @Test void seasonalReplayIsDeterministicAndConservesTotalWater() {
        var initial = new HydrologySeasonalSnowSoilV2.State(0,5);
        var seasons = List.of(forcing(0.2,-3,0.03,12),
            forcing(0.1,5,0.02,12),forcing(0,25,0.1,12));
        var first = HydrologySeasonalSnowSoilV2.simulate(initial,seasons);
        assertEquals(first,HydrologySeasonalSnowSoilV2.simulate(initial,seasons));
        assertEquals(3,first.size());
        assertEquals(0,initial.snowM3(),1e-10);
        double rainAndSnow = 0, evaporation = 0, runoff = 0;
        for (var step : first) {
            rainAndSnow += step.rainM3()+step.snowfallM3();
            evaporation += step.evaporatedM3();
            runoff += step.runoffM3();
            assertEquals(0,step.conservationResidualM3(),1e-9);
        }
        var last = first.get(first.size()-1).state();
        assertEquals(initial.snowM3()+initial.soilM3()+rainAndSnow,
            last.snowM3()+last.soilM3()+evaporation+runoff,1e-9);
    }

    @Test void invalidForcingAndImpossibleStorageFailClosed() {
        assertThrows(IllegalArgumentException.class,() ->
            new HydrologySeasonalSnowSoilV2.State(-1,0));
        assertThrows(IllegalArgumentException.class,() ->
            new HydrologySeasonalSnowSoilV2.Forcing(1,0,0,0,1,0,1));
        assertThrows(IllegalArgumentException.class,() ->
            new HydrologySeasonalSnowSoilV2.Forcing(1,Double.NaN,0,100,1,0,1));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalSnowSoilV2.advance(
                new HydrologySeasonalSnowSoilV2.State(0,5),
                forcing(0,2,0,4)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalSnowSoilV2.advance(
                new HydrologySeasonalSnowSoilV2.State(0,0),
                new HydrologySeasonalSnowSoilV2.Forcing(
                    Double.MAX_VALUE,10,0,Double.MAX_VALUE,1,0,0)));
    }
}
