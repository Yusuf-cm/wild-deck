package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalGlobalRoutingV2Test {
    private static final double RADIUS = 1000;
    private static final GlobalHydrologyAddressV2.Sample POLE =
        new GlobalHydrologyAddressV2.Sample(1,2,0,0);
    private static final GlobalHydrologyAddressV2.Sample WEST =
        new GlobalHydrologyAddressV2.Sample(1,2,7,2);
    private static final GlobalHydrologyAddressV2.Sample EAST =
        new GlobalHydrologyAddressV2.Sample(1,2,0,2);
    private static final GlobalHydrologyAddressV2.Sample OUT =
        new GlobalHydrologyAddressV2.Sample(1,2,1,2);
    private static final GlobalHydrologyAddressV2.Sample UNLOADED =
        new GlobalHydrologyAddressV2.Sample(1,2,6,2);

    private static HydrologySnowSoilStateV2.Forcing forcing(
            GlobalHydrologyAddressV2.Sample address,double precipitation) {
        return HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            address,RADIUS,precipitation,5,0.01,0.15,3,0.02);
    }

    private static HydrologySeasonalRegionBudgetV2.Result season() {
        return HydrologySeasonalRegionBudgetV2.advance(
            new HydrologySnowSoilStateV2.State("world",Map.of()),
            Map.of("west",Map.of(POLE,forcing(POLE,0.3),
                                   WEST,forcing(WEST,0.2)),
                   "east",Map.of(EAST,forcing(EAST,0.1))),RADIUS);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample> complete() {
        var routes = new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        routes.put(POLE,null);
        routes.put(WEST,EAST);
        routes.put(EAST,OUT);
        routes.put(OUT,null); // known terrain route, but no local seasonal rain
        return routes;
    }

    @Test void unloadedKnownMouthReceivesWaterWithoutInventedPrecipitation() {
        var seasonal = season();
        var result = HydrologySeasonalGlobalRoutingV2.route(
            seasonal,complete(),Set.of(POLE,OUT));
        assertTrue(result.drainage().pendingOutflow().isEmpty());
        assertEquals(seasonal.season().runoffVolume(),result.budget().input(),1e-8);
        assertEquals(seasonal.season().runoffVolume(),result.budget().outlet(),1e-8);
        assertEquals(0,result.budget().residual(),1e-8);
        assertEquals(0,result.drainage().discharge().get(OUT)-
            result.drainage().terminalOutflow().get(OUT),1e-8);
        assertEquals(0,result.drainage().discharge().get(POLE)-
            result.drainage().terminalOutflow().get(POLE),1e-8);
        assertEquals(4,result.drainage().discharge().size());
        assertEquals(3*86400.0,result.durationSeconds(),0);
        assertEquals(result.drainage().terminalOutflow().get(OUT)/result.durationSeconds(),
            result.outletM3PerSecond().get(OUT),1e-12);
    }

    @Test void stableCatchmentAndReachIdentitiesIgnoreRegionalPartition() {
        var first = HydrologySeasonalGlobalRoutingV2.route(
            season(),complete(),Set.of(POLE,OUT));
        var base = season();
        var renamed = new HydrologySeasonalRegionBudgetV2.Result(base.season(),
            Map.of("alpha",base.runoffByRegion().get("west"),
                   "beta",base.runoffByRegion().get("east")),
            base.ownedAreaM2(),base.durationDays());
        var second = HydrologySeasonalGlobalRoutingV2.route(
            renamed,complete(),Set.of(OUT,POLE));
        assertEquals(first,second);
        assertEquals(first.catchments().get(WEST),first.catchments().get(EAST));
        assertEquals(first.catchments().get(EAST),first.catchments().get(OUT));
        assertEquals("catchment:v2:"+HydrologyGlobalCatchmentsV2.coordinate(OUT).stableKey(),
            first.catchments().get(WEST).stableKey());
        assertEquals("river-reach:v2:"+HydrologyGlobalCatchmentsV2.coordinate(WEST).stableKey()+
            "->"+HydrologyGlobalCatchmentsV2.coordinate(EAST).stableKey(),
            first.reaches().get(WEST).stableKey());
    }

    @Test void missingUnloadedRouteRemainsPendingWithoutFakeGlobalIdentity() {
        var routes = complete();
        routes.put(WEST,UNLOADED);
        var result = HydrologySeasonalGlobalRoutingV2.route(
            season(),routes,Set.of(POLE,OUT));
        assertTrue(result.drainage().pendingOutflow().containsKey(UNLOADED));
        assertFalse(result.catchments().containsKey(WEST));
        assertEquals(season().season().runoffVolume(),
            result.budget().outlet()+result.budget().frontier(),1e-8);
        assertEquals(result.drainage().pendingOutflow().get(UNLOADED)/result.durationSeconds(),
            result.frontierM3PerSecond().get(UNLOADED),1e-12);
    }

    @Test void knownUnloadedInteriorCanForwardToAnotherUnknownFrontier() {
        var routes = complete();
        routes.put(WEST,UNLOADED);
        routes.put(UNLOADED,new GlobalHydrologyAddressV2.Sample(1,2,5,2));
        var result = HydrologySeasonalGlobalRoutingV2.route(
            season(),routes,Set.of(POLE,OUT));
        assertTrue(result.drainage().pendingOutflow().containsKey(
            new GlobalHydrologyAddressV2.Sample(1,2,5,2)));
        assertFalse(result.catchments().containsKey(WEST));
        assertTrue(result.reaches().containsKey(UNLOADED));
    }

    @Test void sharedSeasonalVertexCannotBeDoubleCountedByNamedRegions() {
        var base = season();
        var bad = new HydrologySeasonalRegionBudgetV2.Result(base.season(),
            Map.of("west",base.runoffByRegion().get("west"),
                   "east",base.runoffByRegion().get("west")),
            base.ownedAreaM2(),base.durationDays());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(bad,complete(),Set.of(POLE,OUT)));
    }

    @Test void missingOrAlteredRegionalRunoffIsRejected() {
        var base = season();
        var missing = new HydrologySeasonalRegionBudgetV2.Result(base.season(),
            Map.of("west",base.runoffByRegion().get("west")),
            base.ownedAreaM2(),base.durationDays());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(missing,complete(),Set.of(POLE,OUT)));
        var altered = new HydrologySeasonalRegionBudgetV2.Result(base.season(),
            Map.of("west",List.of(new HydrologyGlobalSlopeRouterV2.Source(WEST,0)),
                   "east",base.runoffByRegion().get("east")),
            base.ownedAreaM2(),base.durationDays());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(altered,complete(),Set.of(POLE,OUT)));
    }

    @Test void missingKnownSeasonalVertexAndUnknownMouthAreRejected() {
        var routes = complete();
        routes.remove(WEST);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(season(),routes,Set.of(POLE,OUT)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(season(),complete(),Set.of(POLE,UNLOADED)));
    }

    @Test void fakeMouthAndNonAdjacentEdgesAreRejected() {
        var routes = complete();
        routes.put(EAST,OUT);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(season(),routes,Set.of(POLE,EAST)));
        routes.put(EAST,new GlobalHydrologyAddressV2.Sample(1,2,4,2));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(season(),routes,Set.of(POLE,OUT)));
    }

    @Test void noncanonicalAndMixedResolutionOwnersAreRejected() {
        var seam = complete();
        seam.put(new GlobalHydrologyAddressV2.Sample(1,2,8,2),null);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(season(),seam,Set.of(POLE,OUT)));
        var mixed = complete();
        mixed.put(new GlobalHydrologyAddressV2.Sample(2,2,0,4),null);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(season(),mixed,Set.of(POLE,OUT)));
    }

    @Test void cyclesFailRatherThanProducingStableCatchments() {
        var routes = complete();
        routes.put(OUT,EAST);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalGlobalRoutingV2.route(season(),routes,Set.of(POLE)));
    }

    @Test void zeroRainProducesZeroVolumeAndRateWithoutInventingWater() {
        var zero = HydrologySeasonalRegionBudgetV2.advance(
            new HydrologySnowSoilStateV2.State("world",Map.of()),
            Map.of("zero",Map.of(EAST,forcing(EAST,0))),RADIUS);
        var routes = new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        routes.put(EAST,null);
        var result = HydrologySeasonalGlobalRoutingV2.route(zero,routes,Set.of(EAST));
        assertEquals(0,result.budget().input(),0);
        assertEquals(0,result.outletM3PerSecond().get(EAST),0);
    }
}
