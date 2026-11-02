package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalRegionBudgetV2Test {
    private static final double RADIUS = 1000;
    private static final GlobalHydrologyAddressV2.Sample POLE =
        new GlobalHydrologyAddressV2.Sample(1,2,0,0);
    private static final GlobalHydrologyAddressV2.Sample WEST =
        new GlobalHydrologyAddressV2.Sample(1,2,7,2);
    private static final GlobalHydrologyAddressV2.Sample EAST =
        new GlobalHydrologyAddressV2.Sample(1,2,0,2);

    private static HydrologySnowSoilStateV2.Forcing forcing(
            GlobalHydrologyAddressV2.Sample address,double rain,
            double temperature,double days) {
        return HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            address,RADIUS,rain,temperature,0.01,0.15,days,0.02);
    }

    private static Map<String,Map<GlobalHydrologyAddressV2.Sample,
            HydrologySnowSoilStateV2.Forcing>> winter() {
        return Map.of(
            "west",Map.of(POLE,forcing(POLE,0.3,-4,3),
                          WEST,forcing(WEST,0.2,5,3)),
            "east",Map.of(EAST,forcing(EAST,0.1,5,3)));
    }

    private static HydrologySnowSoilStateV2.State initial() {
        return new HydrologySnowSoilStateV2.State("world",Map.of());
    }

    @Test void twoRegionsConserveOwnedSnowSoilAndRunoffExactlyOnce() {
        var result = HydrologySeasonalRegionBudgetV2.advance(
            initial(),winter(),RADIUS);
        assertEquals(3,result.season().state().cells().size());
        assertEquals(2,result.runoffByRegion().get("west").size());
        assertEquals(1,result.runoffByRegion().get("east").size());
        assertEquals(3,result.durationDays(),0);
        double sum = result.runoffByRegion().values().stream()
            .flatMap(List::stream)
            .mapToDouble(HydrologyGlobalSlopeRouterV2.Source::localWater).sum();
        assertEquals(result.season().runoffVolume(),sum,1e-8);
        assertEquals(result.season().initialVolume()+
            result.season().precipitationVolume(),
            result.season().evaporationVolume()+result.season().runoffVolume()+
            result.season().storedVolume(),1e-8);
        assertEquals(HydrologySphericalCellAreaV2.areaAt(POLE,RADIUS)+
            HydrologySphericalCellAreaV2.areaAt(WEST,RADIUS)+
            HydrologySphericalCellAreaV2.areaAt(EAST,RADIUS),
            result.ownedAreaM2(),1e-8);
    }

    @Test void shuffledRegionOrderDoesNotChangeAnyRunoffOrStorage() {
        var original = HydrologySeasonalRegionBudgetV2.advance(
            initial(),winter(),RADIUS);
        var reversed = new LinkedHashMap<String,Map<GlobalHydrologyAddressV2.Sample,
            HydrologySnowSoilStateV2.Forcing>>();
        reversed.put("west",winter().get("west"));
        reversed.put("east",winter().get("east"));
        assertEquals(original,HydrologySeasonalRegionBudgetV2.advance(
            initial(),reversed,RADIUS));
    }

    @Test void sharedBoundaryOrPolarVertexCannotReceiveRainTwice() {
        var duplicate = new HashMap<>(winter());
        duplicate.put("east",Map.of(POLE,forcing(POLE,0.3,-4,3)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),duplicate,RADIUS));
    }

    @Test void mixedResolutionAndNoncanonicalSeamAreRejected() {
        var mixed = new HashMap<>(winter());
        mixed.put("east",Map.of(new GlobalHydrologyAddressV2.Sample(2,2,0,4),
            forcing(new GlobalHydrologyAddressV2.Sample(2,2,0,4),0.1,5,3)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),mixed,RADIUS));
        var seam = new GlobalHydrologyAddressV2.Sample(1,2,8,2);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),
                Map.of("seam",Map.of(seam,forcing(seam,0.1,5,3))),RADIUS));
    }

    @Test void forgedAreaAndConflictingSeasonDurationAreRejected() {
        var badArea = new HashMap<>(winter());
        badArea.put("east",Map.of(EAST,
            new HydrologySnowSoilStateV2.Forcing(
                0.1,5,0.01,0.15,3,0.02,42)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),badArea,RADIUS));
        var differentDays = new HashMap<>(winter());
        differentDays.put("east",Map.of(EAST,forcing(EAST,0.1,5,4)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),differentDays,RADIUS));
    }

    @Test void persistedCellsCannotBeDroppedAcrossRegionalSeasons() {
        var first = HydrologySeasonalRegionBudgetV2.advance(initial(),winter(),RADIUS);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(first.season().state(),
                Map.of("west",winter().get("west")),RADIUS));
        var spring = Map.of(
            "east",Map.of(EAST,forcing(EAST,0,6,4)),
            "west",Map.of(POLE,forcing(POLE,0,8,4),
                          WEST,forcing(WEST,0,8,4)));
        var second = HydrologySeasonalRegionBudgetV2.advance(
            first.season().state(),spring,RADIUS);
        assertEquals(first.season().storedVolume(),
            second.season().initialVolume(),1e-8);
        assertEquals(second,HydrologySeasonalRegionBudgetV2.advance(
            first.season().state(),spring,RADIUS));
        assertEquals(first.season().storedVolume(),
            second.season().evaporationVolume()+second.season().runoffVolume()+
            second.season().storedVolume(),1e-8);
    }

    @Test void invalidOrEmptyRegionInventoryFailsClosed() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),Map.of(),RADIUS));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),
                Map.of("empty",Map.of()),RADIUS));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),
                Map.of(" ",winter().get("west")),RADIUS));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalRegionBudgetV2.advance(initial(),winter(),-1));
    }

    @Test void crossRegionSeamFlowUsesOneGlobalCatchmentAndConservesRunoff() {
        var season = HydrologySeasonalRegionBudgetV2.advance(
            initial(),winter(),RADIUS);
        var routed = season.route(Map.of(WEST,EAST),Set.of(POLE,EAST));
        double west = season.season().runoff().stream()
            .filter(s -> s.address().equals(WEST)).findFirst().orElseThrow().localWater();
        double east = season.season().runoff().stream()
            .filter(s -> s.address().equals(EAST)).findFirst().orElseThrow().localWater();
        assertEquals(west+east,routed.drainage().terminalOutflow().get(EAST),1e-8);
        assertEquals(EAST,routed.drainage().resolvedCatchments().get(WEST));
        assertTrue(routed.drainage().pendingOutflow().isEmpty());
        assertEquals(season.season().runoffVolume(),routed.budget().input(),1e-8);
        assertEquals(season.season().runoffVolume(),routed.budget().outlet(),1e-8);
        assertEquals(0,routed.budget().residual(),1e-8);
    }

    @Test void unloadedDownstreamRemainsPendingInsteadOfBecomingFakeMouth() {
        var season = HydrologySeasonalRegionBudgetV2.advance(
            initial(),winter(),RADIUS);
        var unloaded = new GlobalHydrologyAddressV2.Sample(1,2,6,2);
        var routed = season.route(Map.of(WEST,unloaded),Set.of(POLE,EAST));
        double west = season.season().runoff().stream()
            .filter(s -> s.address().equals(WEST)).findFirst().orElseThrow().localWater();
        assertEquals(west,routed.drainage().pendingOutflow().get(unloaded),1e-8);
        assertFalse(routed.drainage().resolvedCatchments().containsKey(WEST));
        assertEquals(season.season().runoffVolume(),
            routed.budget().outlet()+routed.budget().frontier(),1e-8);
    }

    @Test void invalidOrForgedSeasonalRoutesFailBeforeReportingDischarge() {
        var season = HydrologySeasonalRegionBudgetV2.advance(
            initial(),winter(),RADIUS);
        var unknown = new GlobalHydrologyAddressV2.Sample(1,2,5,2);
        var seamAlias = new GlobalHydrologyAddressV2.Sample(1,2,8,2);
        assertThrows(IllegalArgumentException.class,() ->
            season.route(Map.of(unknown,EAST),Set.of(EAST)));
        assertThrows(IllegalArgumentException.class,() ->
            season.route(Map.of(WEST,seamAlias),Set.of(EAST)));
        assertThrows(IllegalArgumentException.class,() ->
            season.route(Map.of(WEST,EAST),Set.of(WEST)));
        assertThrows(IllegalArgumentException.class,() ->
            season.route(Map.of(),Set.of(unknown)));
    }
}
