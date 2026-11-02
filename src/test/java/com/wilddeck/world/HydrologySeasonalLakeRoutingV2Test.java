package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLakeRoutingV2Test {
    private static final GlobalHydrologyAddressV2.Sample A = sample(1);
    private static final GlobalHydrologyAddressV2.Sample B = sample(2);
    private static final GlobalHydrologyAddressV2.Sample SPILL = sample(3);
    private static final GlobalHydrologyAddressV2.Sample E = sample(4);
    private static final GlobalHydrologyAddressV2.Sample C = sample(5);
    private static final GlobalHydrologyAddressV2.Sample OUT = sample(6);

    private static GlobalHydrologyAddressV2.Sample sample(int x) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,2);
    }

    private static HydrologySeasonalGlobalRoutingV2.Result routed() {
        var nodes = List.of(
            new HydrologyFlowGraphV2.Node(A,5,B,false),
            new HydrologyFlowGraphV2.Node(B,0,null,false),
            new HydrologyFlowGraphV2.Node(C,4,E,false),
            new HydrologyFlowGraphV2.Node(E,0,null,false),
            new HydrologyFlowGraphV2.Node(OUT,2,null,true));
        var flow = HydrologyFlowGraphV2.solve(nodes);
        var budget = HydrologyConservationV2.verify(nodes,flow);
        return new HydrologySeasonalGlobalRoutingV2.Result(flow,budget,
            Map.of(),Map.of(),Map.of(OUT,2.0/86400),
            Map.of(B,5.0/86400,E,4.0/86400),86400);
    }

    private static HydrologySeasonalLakeRoutingV2.Lake lake(
            double capacity,double previous,double evaporation,
            GlobalHydrologyAddressV2.Sample spill) {
        return new HydrologySeasonalLakeRoutingV2.Lake(
            new LakeReservoirV2.Basin(new double[]{0},
                new double[]{1},capacity),previous,evaporation,spill);
    }

    @Test void filledLakeRetainsWaterAndExportsOnlyOverflow() {
        var result = HydrologySeasonalLakeRoutingV2.advance(routed(),
            Map.of(B,lake(6,4,0,SPILL)));
        assertEquals(5,result.lakeInflowM3().get(B),0);
        assertEquals(6,result.lakes().get(B).volume(),1e-12);
        assertEquals(3,result.lakes().get(B).overflow(),1e-12);
        assertEquals(3,result.pendingM3().get(SPILL),1e-12);
        assertEquals(4,result.pendingM3().get(E),1e-12);
        assertEquals(2,result.verifiedOutletM3(),1e-12);
        assertEquals(0,result.residualM3(),1e-8);
    }

    @Test void changingWettedAreaChangesEvaporationWithoutLosingWater() {
        var basin = new LakeReservoirV2.Basin(new double[]{0,2},
            new double[]{1,1},4);
        var result = HydrologySeasonalLakeRoutingV2.advance(routed(),
            Map.of(B,new HydrologySeasonalLakeRoutingV2.Lake(
                basin,0,1,SPILL)));
        assertEquals(3,result.lakes().get(B).volume(),1e-8);
        assertEquals(2,result.lakes().get(B).evaporated(),1e-8);
        assertEquals(0,result.lakes().get(B).overflow(),1e-8);
        assertFalse(result.pendingM3().containsKey(B));
        assertEquals(0,result.residualM3(),1e-8);
    }

    @Test void evaporationDrawsOnPersistedLakeStorageWithZeroNewRain() {
        var empty = new HydrologySeasonalGlobalRoutingV2.Result(
            HydrologyFlowGraphV2.solve(List.of(
                new HydrologyFlowGraphV2.Node(B,0,null,false))),
            new HydrologyConservationV2.Budget(0,0,0,0),
            Map.of(),Map.of(),Map.of(),Map.of(B,0.0),86400);
        var result = HydrologySeasonalLakeRoutingV2.advance(empty,
            Map.of(B,lake(10,4,1,SPILL)));
        assertEquals(3,result.finalLakeStorageM3(),1e-8);
        assertEquals(1,result.lakeEvaporationM3(),1e-8);
        assertEquals(0,result.pendingM3().size());
        assertEquals(0,result.residualM3(),1e-8);
    }

    @Test void twoIndependentLakeSpillsConvergeWithoutDoubleCounting() {
        var lakes = Map.of(B,lake(2,0,0,SPILL),
                           E,lake(1,0,0,SPILL));
        var first = HydrologySeasonalLakeRoutingV2.advance(routed(),lakes);
        var reversed = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
            HydrologySeasonalLakeRoutingV2.Lake>();
        reversed.put(E,lakes.get(E));
        reversed.put(B,lakes.get(B));
        var second = HydrologySeasonalLakeRoutingV2.advance(routed(),reversed);
        assertEquals(first,second);
        assertEquals(6,first.pendingM3().get(SPILL),1e-12);
        assertEquals(6.0/86400,first.pendingM3PerSecond().get(SPILL),1e-12);
        assertEquals(3,first.finalLakeStorageM3(),1e-12);
        assertEquals(0,first.residualM3(),1e-8);
    }

    @Test void rejectsLakeAtVerifiedOceanMouthOrMissingFrontier() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(routed(),
                Map.of(OUT,lake(10,0,0,sample(7)))));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(routed(),
                Map.of(SPILL,lake(10,0,0,E))));
    }

    @Test void rejectsNonadjacentSpillsAndInstantLakeCascades() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(routed(),
                Map.of(B,lake(10,0,0,C))));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(routed(),
                Map.of(B,lake(10,0,0,E),E,lake(10,0,0,SPILL))));
    }

    @Test void rejectsForgedWaterBudgetsBeforeConsumingInflow() {
        var baseline = routed();
        var forged = new HydrologySeasonalGlobalRoutingV2.Result(
            baseline.drainage(),new HydrologyConservationV2.Budget(100,2,9,89),
            baseline.catchments(),baseline.reaches(),baseline.outletM3PerSecond(),
            baseline.frontierM3PerSecond(),baseline.durationSeconds());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(forged,
                Map.of(B,lake(10,0,0,SPILL))));
    }

    @Test void lakeOutputsAreImmutableAndInputsRemainUnchanged() {
        var before = routed();
        var result = HydrologySeasonalLakeRoutingV2.advance(before,
            Map.of(B,lake(6,4,0,SPILL)));
        assertEquals(5,before.drainage().pendingOutflow().get(B),0);
        assertThrows(UnsupportedOperationException.class,() ->
            result.pendingM3().put(A,99.0));
        assertThrows(UnsupportedOperationException.class,() ->
            result.lakes().clear());
    }

    @Test void realAuditedSeasonalRunoffCanFillLakeWithoutInventedMouth() {
        var forcing = HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            B,1000,0.3,5,0.01,0.15,3,0.02);
        var season = HydrologySeasonalRegionBudgetV2.advance(
            new HydrologySnowSoilStateV2.State("world",Map.of()),
            Map.of("region",Map.of(B,forcing)),1000);
        var known = new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        known.put(B,null);
        var routed = season.routeKnownNetwork(known,Set.of());
        var result = HydrologySeasonalLakeRoutingV2.advance(routed,
            Map.of(B,lake(1,0,0,SPILL)));
        assertEquals(season.season().runoffVolume(),result.seasonalRunoffM3(),1e-8);
        assertEquals(1,result.finalLakeStorageM3(),1e-8);
        assertEquals(season.season().runoffVolume()-1,
            result.pendingM3().get(SPILL),1e-8);
        assertEquals(0,result.residualM3(),1e-8);
    }
    @Test void regionalConvenienceMatchesExplicitLakeAdvancement() {
        var forcing = HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            B,1000,0.3,5,0.01,0.15,3,0.02);
        var season = HydrologySeasonalRegionBudgetV2.advance(
            new HydrologySnowSoilStateV2.State("world",Map.of()),
            Map.of("region",Map.of(B,forcing)),1000);
        var routes = new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        routes.put(B,null);
        var lakes = Map.of(B,lake(1,0,0,SPILL));
        assertEquals(HydrologySeasonalLakeRoutingV2.advance(
            season.routeKnownNetwork(routes,Set.of()),lakes),
            season.routeKnownNetworkWithLakes(routes,Set.of(),lakes));
    }

    @Test void overlappingPendingAndVerifiedOutletsAreRejected() {
        var original = routed();
        var forgedFlow = new HydrologyFlowGraphV2.Result(
            original.drainage().discharge(),
            Map.of(B,5.0,OUT,2.0),
            original.drainage().pendingOutflow(),
            original.drainage().resolvedCatchments());
        var forged = new HydrologySeasonalGlobalRoutingV2.Result(
            forgedFlow,new HydrologyConservationV2.Budget(16,7,9,0),
            Map.of(),Map.of(),Map.of(),Map.of(),86400);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(forged,Map.of()));
    }

    @Test void noLakeInventoryPreservesPendingWaterWithoutCreatingOutlets() {
        var before = routed();
        var after = HydrologySeasonalLakeRoutingV2.advance(before,Map.of());
        assertEquals(before.drainage().pendingOutflow(),after.pendingM3());
        assertEquals(0,after.previousLakeStorageM3(),0);
        assertEquals(0,after.finalLakeStorageM3(),0);
        assertEquals(0,after.lakeEvaporationM3(),0);
        assertEquals(0,after.residualM3(),1e-8);
    }

    @Test void invalidPriorStorageAndSpillIntoKnownMouthAreRejected() {
        assertThrows(IllegalArgumentException.class,() ->
            lake(1,2,0,SPILL));
        var nodes = List.of(
            new HydrologyFlowGraphV2.Node(B,5,null,false),
            new HydrologyFlowGraphV2.Node(SPILL,0,null,true));
        var flow = HydrologyFlowGraphV2.solve(nodes);
        var budget = HydrologyConservationV2.verify(nodes,flow);
        var routed = new HydrologySeasonalGlobalRoutingV2.Result(
            flow,budget,Map.of(),Map.of(),Map.of(),Map.of(),86400);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(routed,
                Map.of(B,lake(1,0,0,SPILL))));
    }

    @Test void substeppedLakeRoutingConservesWaterAndDefersSpill() {
        var before = routed();
        var forcing = Map.of(B,lake(6,4,1,SPILL));
        var legacy = HydrologySeasonalLakeRoutingV2.advance(before,forcing);
        var timed = HydrologySeasonalLakeRoutingV2.advanceSubstepped(before,forcing,10);
        assertNotEquals(legacy.lakes().get(B),timed.lakes().get(B));
        assertEquals(15, timed.seasonalRunoffM3()+timed.previousLakeStorageM3(),1e-10);
        assertEquals(15,timed.verifiedOutletM3()+
            timed.pendingM3().values().stream().mapToDouble(Double::doubleValue).sum()+
            timed.lakeEvaporationM3()+timed.finalLakeStorageM3(),1e-8);
        assertEquals(0,timed.residualM3(),1e-8);
        assertEquals(5,before.drainage().pendingOutflow().get(B),0);
    }

    @Test void rejectsForgedSeasonalRateEvenWhenVolumeBudgetBalances() {
        var baseline = routed();
        var forged = new HydrologySeasonalGlobalRoutingV2.Result(
            baseline.drainage(),baseline.budget(),baseline.catchments(),
            baseline.reaches(),Map.of(OUT,999.0),
            baseline.frontierM3PerSecond(),baseline.durationSeconds());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advance(forged,
                Map.of(B,lake(6,4,0,SPILL))));
    }

    @Test void rejectsInvalidSubstepCountBeforeAnyLakeAdvancement() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advanceSubstepped(routed(),
                Map.of(B,lake(6,4,0,SPILL)),0));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeRoutingV2.advanceSubstepped(routed(),
                Map.of(B,lake(6,4,0,SPILL)),4097));
    }
}
