package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalPendingOutletV2Test {
    private static final GlobalHydrologyAddressV2.Sample A=at(2,2);
    private static final GlobalHydrologyAddressV2.Sample B=at(3,2);
    private static final GlobalHydrologyAddressV2.Sample C=at(4,2);
    private static final GlobalHydrologyAddressV2.Sample D=at(5,2);
    private static final GlobalHydrologyAddressV2.Sample LAKE=at(6,2);

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static HydrologySeasonalPendingOutletV2.Result route(
            Map<GlobalHydrologyAddressV2.Sample,Double> pending,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakes,
            Set<GlobalHydrologyAddressV2.Sample> mouths) {
        return HydrologySeasonalPendingOutletV2.route(
            pending,downstream,loaded,lakes,mouths);
    }

    @Test void loadedCrossRegionWaterReachesVerifiedMouthWithStableCatchment() {
        var result=route(Map.of(A,3.0),Map.of(A,B,B,C,C,D,D,LAKE),
            Set.of(A,B,C,D,LAKE),Set.of(),Set.of(LAKE));
        assertEquals(Map.of(LAKE,3.0),result.verifiedOutletM3());
        assertTrue(result.pendingM3().isEmpty());
        assertEquals(3,result.inputM3(),1e-12);
        assertEquals(3,result.outletTotalM3(),1e-12);
        assertEquals(new HydrologyGlobalCatchmentsV2.CatchmentId(
            HydrologyGlobalCatchmentsV2.coordinate(LAKE)),
            result.sourceCatchments().get(A));
        assertEquals(4,result.traversedReaches().size());
    }

    @Test void unloadedMouthRemainsPendingUntilLoadedWithoutInventingOcean() {
        var edges=new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        edges.put(A,B);edges.put(B,C);edges.put(C,null);
        var first=route(Map.of(A,5.0),edges,Set.of(A,B),Set.of(),Set.of(C));
        assertEquals(Map.of(C,5.0),first.pendingM3());
        assertTrue(first.verifiedOutletM3().isEmpty());
        var second=route(first.pendingM3(),edges,Set.of(C),Set.of(),Set.of(C));
        assertEquals(Map.of(C,5.0),second.verifiedOutletM3());
        assertTrue(second.pendingM3().isEmpty());
    }

    @Test void confluenceConservesWaterAndIsIndependentOfInputIterationOrder() {
        var edges=new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        edges.put(A,B);edges.put(C,B);edges.put(B,null);
        var forward=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        forward.put(A,2.5);forward.put(C,3.75);
        var reverse=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        reverse.put(C,3.75);reverse.put(A,2.5);
        var one=route(forward,edges,Set.of(A,B,C),Set.of(),Set.of(B));
        var two=route(reverse,edges,Set.of(A,B,C),Set.of(),Set.of(B));
        assertEquals(one,two);
        assertEquals(Map.of(B,6.25),one.verifiedOutletM3());
        assertEquals(2,one.sourceCatchments().size());
        assertEquals(6.25,one.inputM3(),1e-12);
        assertThrows(UnsupportedOperationException.class,
            ()->one.verifiedOutletM3().put(B,99.0));
    }

    @Test void lakeInletStopsFlowWithoutPretendingItIsAnOcean() {
        var edges=new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        edges.put(A,B);edges.put(B,C);edges.put(C,null);
        var result=route(Map.of(A,4.0),edges,Set.of(A,B,C),
            Set.of(B),Set.of(C));
        assertEquals(Map.of(B,4.0),result.pendingM3());
        assertTrue(result.verifiedOutletM3().isEmpty());
        assertTrue(result.sourceCatchments().isEmpty());
        assertEquals(1,result.traversedReaches().size());
    }

    @Test void unknownLoadedTerminalStaysPendingNotVerified() {
        var result=route(Map.of(A,4.0),Map.of(A,B),Set.of(A,B),
            Set.of(),Set.of());
        assertEquals(Map.of(B,4.0),result.pendingM3());
        assertEquals(0,result.outletTotalM3(),1e-12);
    }

    @Test void rejectsFalseMouthAndCyclesAndNonadjacentEdges() {
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(A,1.0),Map.of(A,B),Set.of(A,B),
                Set.of(),Set.of(A)));
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(A,1.0),Map.of(A,B,B,A),Set.of(A,B),
                Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(A,1.0),Map.of(A,D),Set.of(A,D),
                Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(A,1.0),Map.of(A,B),Set.of(A,B),
                Set.of(B),Set.of(B)));
    }

    @Test void rejectsDuplicatePhysicalVertexAcrossResolutionsAndNoncanonicalSeam() {
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,4,2);
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(C,2.0,alias,1.0),Map.of(),
                Set.of(C),Set.of(),Set.of()));
        var seam=new GlobalHydrologyAddressV2.Sample(1,2,8,2);
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(seam,1.0),Map.of(),Set.of(),
                Set.of(),Set.of()));
    }

    @Test void rejectsNegativeNonfiniteAndAggregateOverflowBeforeAnyDischarge() {
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(A,-1.0),Map.of(),Set.of(),Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(A,Double.NaN),Map.of(),Set.of(),Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            route(Map.of(A,Double.MAX_VALUE,C,Double.MAX_VALUE),
                Map.of(),Set.of(),Set.of(),Set.of()));
    }

    @Test void stableCatchmentKeySurvivesSamplingResolutionChange() {
        var lowSource=new GlobalHydrologyAddressV2.Sample(0,2,1,1);
        var lowMouth=new GlobalHydrologyAddressV2.Sample(0,2,2,1);
        var highSource=new GlobalHydrologyAddressV2.Sample(1,2,3,2);
        var highMouth=new GlobalHydrologyAddressV2.Sample(1,2,4,2);
        var low=new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        low.put(lowSource,lowMouth);low.put(lowMouth,null);
        var high=new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        high.put(highSource,highMouth);high.put(highMouth,null);
        var one=route(Map.of(lowSource,2.0),low,
            Set.of(lowSource,lowMouth),Set.of(),Set.of(lowMouth));
        var two=route(Map.of(highSource,2.0),high,
            Set.of(highSource,highMouth),Set.of(),Set.of(highMouth));
        assertEquals(one.sourceCatchments().get(lowSource).stableKey(),
            two.sourceCatchments().get(highSource).stableKey());
    }

    @Test void seamAdjacentRouteHasStableGeographicReach() {
        var west=at(7,2);
        var east=at(0,2);
        var edges=new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        edges.put(west,east);edges.put(east,null);
        var result=route(Map.of(west,7.0),edges,Set.of(west,east),
            Set.of(),Set.of(east));
        assertEquals(Map.of(east,7.0),result.verifiedOutletM3());
        assertEquals(1,result.traversedReaches().size());
    }
}
