package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLakeCarryoverV2Test {
    private static final GlobalHydrologyAddressV2.Sample A = sample(2,2);
    private static final GlobalHydrologyAddressV2.Sample B = sample(3,2);
    private static final GlobalHydrologyAddressV2.Sample C = sample(3,1);
    private static final GlobalHydrologyAddressV2.Sample OUT = sample(6,2);

    private static GlobalHydrologyAddressV2.Sample sample(int x,int y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static HydrologySeasonalGlobalRoutingV2.Result routed(double a,double b) {
        var nodes=List.of(new HydrologyFlowGraphV2.Node(A,a,null,false),
            new HydrologyFlowGraphV2.Node(B,b,null,false),
            new HydrologyFlowGraphV2.Node(OUT,2,null,true));
        var flow=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,flow);
        return new HydrologySeasonalGlobalRoutingV2.Result(flow,budget,
            Map.of(),Map.of(),Map.of(OUT,2.0/86400),
            Map.of(A,a/86400,B,b/86400),86400);
    }

    private static HydrologySeasonalLaggedLakeNetworkV2.Lake lake(double previous) {
        return new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2),
            previous,0,B,1);
    }

    @Test void priorWaterEntersLakeOnceWithoutBecomingRain() {
        var next=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0,0),Map.of(A,lake(0)),3,Map.of(A,9.0));
        assertEquals(9,next.result().lakeInflowM3().get(A),1e-12);
        assertEquals(2,next.result().lakes().get(A).volume(),1e-12);
        assertEquals(7,next.result().pendingM3().get(B),1e-12);
        assertEquals(2,next.result().seasonalRunoffM3(),1e-12);
        assertEquals(9,next.carryoverM3().get(A),1e-12);
        assertEquals(0,next.result().residualM3(),1e-10);
    }

    @Test void newRainAndOldCarryoverShareAnchorWithoutDuplication() {
        var next=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(3,0),Map.of(A,lake(0)),2,Map.of(A,4.0));
        assertEquals(7,next.result().lakeInflowM3().get(A),1e-12);
        assertEquals(5,next.result().pendingM3().get(B),1e-12);
        assertEquals(5,next.result().seasonalRunoffM3(),1e-12);
        assertEquals(0,next.result().residualM3(),1e-10);
    }

    @Test void nonlakeCarryoverRemainsPendingRatherThanBecomingOceanWater() {
        var next=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0,0),Map.of(),1,Map.of(C,4.0));
        assertEquals(4,next.result().pendingM3().get(C),1e-12);
        assertEquals(2,next.result().verifiedOutletM3(),1e-12);
        assertEquals(0,next.result().residualM3(),1e-10);
    }

    @Test void chronologicalSchedulesOnlyDescribeNewRunoff() {
        var schedule=Map.of(A,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(3,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0)));
        var next=HydrologySeasonalLaggedLakeNetworkV2.advanceScheduledWithCarryover(
            routed(3,0),Map.of(A,lake(0)),schedule,Map.of(A,4.0));
        assertEquals(7,next.result().lakeInflowM3().get(A),1e-12);
        assertEquals(0,next.result().residualM3(),1e-10);
    }

    @Test void rejectsPhysicalAliasOfOriginalRunoff() {
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,2,2);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
                routed(3,0),Map.of(A,lake(0)),2,Map.of(alias,4.0)));
    }

    @Test void rejectsCarryoverToAuthenticatedOceanOutlet() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
                routed(0,0),Map.of(),2,Map.of(OUT,4.0)));
    }

    @Test void rejectsNegativeNonfiniteAndNullCarryover() {
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,() ->
                HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
                    routed(0,0),Map.of(),2,Map.of(C,invalid)));
        }
        assertThrows(NullPointerException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
                routed(0,0),Map.of(),2,null));
    }

    @Test void emptyCarryoverPreservesLegacyResult() {
        var expected=HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
            routed(3,0),Map.of(A,lake(0)),2);
        var actual=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(3,0),Map.of(A,lake(0)),2,Map.of());
        assertEquals(expected,actual.result());
        assertTrue(actual.carryoverM3().isEmpty());
    }
}
