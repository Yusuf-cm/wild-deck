package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionDischargeV2Test {
    private static GlobalHydrologyAddressV2.Sample coarse(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(3,4,x,y);
    }
    private static HydrologyMixedResolutionDischargeV2.Node node(
            GlobalHydrologyAddressV2.Sample at,double water,
            GlobalHydrologyAddressV2.Sample next,boolean terminal) {
        return new HydrologyMixedResolutionDischargeV2.Node(at,water,next,terminal);
    }

    @Test void mixedLodConfluenceRoutesEverySourceOnceToVerifiedMouth() {
        var a=coarse(4,4); var b=fine(10,8); var c=fine(11,8);
        var d=fine(10,7); var mouth=fine(12,8);
        var graph=List.of(node(a,2,coarse(5,4),false),
            node(b,3,c,false),node(c,5,mouth,false),
            node(d,7,c,false),node(mouth,11,null,true));
        var result=HydrologyMixedResolutionDischargeV2.solve(graph,Set.of(mouth));
        assertEquals(28.0,result.terminalOutflow().get(mouth));
        assertEquals(28.0,result.discharge().get(mouth));
        assertEquals(5,result.discharge().size());
        assertTrue(result.pendingOutflow().isEmpty());
        assertEquals(1,new HashSet<>(result.resolvedCatchments().values()).size());
        var reversed=new ArrayList<>(graph);
        Collections.reverse(reversed);
        assertEquals(result,HydrologyMixedResolutionDischargeV2.solve(reversed,Set.of(mouth)));
    }

    @Test void fineSourceCanDischargeIntoCoarseOwnedDestination() {
        var fineSource=fine(11,8); var coarseMouth=coarse(6,4);
        var result=HydrologyMixedResolutionDischargeV2.solve(List.of(
            node(fineSource,13,coarseMouth,false),node(coarseMouth,4,null,true)),Set.of(coarseMouth));
        assertEquals(17.0,result.terminalOutflow().get(coarseMouth));
        assertEquals(result.resolvedCatchments().get(fineSource),
                     result.resolvedCatchments().get(coarseMouth));
    }

    @Test void unloadedBoundaryAliasesAggregateAtOnePhysicalCoordinate() {
        var a=coarse(4,4);var b=coarse(5,5);var frontier=coarse(5,4);
        var result=HydrologyMixedResolutionDischargeV2.solve(List.of(
            node(a,7,frontier,false),node(b,11,fine(10,8),false)),Set.of());
        assertEquals(1,result.pendingOutflow().size());
        assertEquals(18.0,result.pendingOutflow().get(HydrologyGlobalCatchmentsV2.coordinate(frontier)));
        assertTrue(result.resolvedCatchments().isEmpty());
        assertTrue(result.terminalOutflow().isEmpty());
    }

    @Test void unloadedLocalSinkIsPendingNotFalseGlobalCatchment() {
        var a=coarse(4,4);
        var result=HydrologyMixedResolutionDischargeV2.solve(List.of(node(a,9,null,false)),Set.of());
        assertEquals(9.0,result.pendingOutflow().get(HydrologyGlobalCatchmentsV2.coordinate(a)));
        assertTrue(result.resolvedCatchments().isEmpty());
    }

    @Test void rejectsDuplicatePhysicalOwnersAcrossLods() {
        var a=coarse(4,4);var alias=fine(8,8);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solve(List.of(
                node(a,7,null,false),node(alias,11,null,false)),Set.of()));
    }

    @Test void rejectsCrossLodCyclesTeleportsAndFakeMouths() {
        var a=coarse(4,4);var b=fine(10,8);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solve(List.of(
                node(a,1,b,false),node(b,2,a,false)),Set.of()));
        assertThrows(IllegalArgumentException.class,()->node(a,1,coarse(10,4),false));
        assertThrows(IllegalArgumentException.class,()->node(a,1,fine(8,8),false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solve(List.of(node(a,1,null,true)),Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solve(List.of(node(a,1,null,false)),Set.of(a)));
    }

    @Test void seamAndPoleNeighborsAreGeographicallyAdjacent() {
        var seamWest=coarse(0,5);var seamEast=coarse(31,5);
        var pole=coarse(0,0);var ring=coarse(15,1);
        var first=HydrologyMixedResolutionDischargeV2.solve(List.of(
            node(seamWest,2,seamEast,false),node(seamEast,3,null,true)),Set.of(seamEast));
        assertEquals(5.0,first.terminalOutflow().get(seamEast));
        var second=HydrologyMixedResolutionDischargeV2.solve(List.of(
            node(ring,4,pole,false),node(pole,5,null,true)),Set.of(pole));
        assertEquals(9.0,second.terminalOutflow().get(pole));
    }

    @Test void rejectsInvalidWaterAndOverflowWithoutPublishingPartialResults() {
        var a=coarse(4,4);var b=coarse(5,4);
        assertThrows(IllegalArgumentException.class,()->node(a,Double.NaN,null,false));
        assertThrows(IllegalArgumentException.class,()->node(a,-1,null,false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solve(List.of(
                node(a,1e308,b,false),node(b,1e308,null,true)),Set.of(b)));
    }

    @Test void outputIsImmutableAndHasNoUnownedDischargeVertices() {
        var a=coarse(4,4);var b=fine(10,8);
        var result=HydrologyMixedResolutionDischargeV2.solve(List.of(
            node(a,2,b,false),node(b,3,null,true)),Set.of(b));
        assertEquals(Set.of(a,b),result.discharge().keySet());
        assertThrows(UnsupportedOperationException.class,()->result.discharge().clear());
        assertThrows(UnsupportedOperationException.class,()->result.resolvedCatchments().clear());
    }

    @Test void unloadedMixedResolutionFrontierCanResumeWithoutNewRainfall() {
        var source=coarse(4,4);var landing=coarse(5,4);var mouth=fine(12,8);
        var first=HydrologyMixedResolutionDischargeV2.solve(List.of(
            node(source,7,fine(10,8),false)),Set.of());
        var second=HydrologyMixedResolutionDischargeV2.solveWithIncoming(List.of(
            node(landing,3,mouth,false),node(mouth,5,null,true)),Set.of(mouth),
            first.pendingOutflow());
        assertEquals(15.0,second.terminalOutflow().get(mouth));
        assertTrue(second.pendingOutflow().isEmpty());
        assertEquals(10.0,second.discharge().get(landing));
    }

    @Test void receivingRejectsUnownedNonfiniteAndOverflowingWater() {
        var landing=coarse(5,4);
        var graph=List.of(node(landing,Double.MAX_VALUE,null,true));
        var coordinate=HydrologyGlobalCatchmentsV2.coordinate(landing);
        var missing=HydrologyGlobalCatchmentsV2.coordinate(coarse(6,4));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solveWithIncoming(graph,Set.of(landing),Map.of(missing,3.0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solveWithIncoming(graph,Set.of(landing),Map.of(coordinate,-1.0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solveWithIncoming(graph,Set.of(landing),Map.of(coordinate,Double.NaN)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionDischargeV2.solveWithIncoming(graph,Set.of(landing),Map.of(coordinate,Double.MAX_VALUE)));
    }
}
