package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLaggedLakeNetworkV2Test {
    private static final GlobalHydrologyAddressV2.Sample A = sample(2,2);
    private static final GlobalHydrologyAddressV2.Sample B = sample(3,2);
    private static final GlobalHydrologyAddressV2.Sample C = sample(3,1);
    private static final GlobalHydrologyAddressV2.Sample SPILL = sample(4,2);
    private static final GlobalHydrologyAddressV2.Sample OUT = sample(6,2);

    private static GlobalHydrologyAddressV2.Sample sample(int x,int y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static HydrologySeasonalGlobalRoutingV2.Result routed(
            double a,double b,double c) {
        var nodes = new ArrayList<HydrologyFlowGraphV2.Node>();
        nodes.add(new HydrologyFlowGraphV2.Node(A,a,null,false));
        nodes.add(new HydrologyFlowGraphV2.Node(B,b,null,false));
        if (c>=0) nodes.add(new HydrologyFlowGraphV2.Node(C,c,null,false));
        nodes.add(new HydrologyFlowGraphV2.Node(OUT,2,null,true));
        var flow=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,flow);
        var rates=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        rates.put(A,a/86400);
        rates.put(B,b/86400);
        if (c>=0) rates.put(C,c/86400);
        return new HydrologySeasonalGlobalRoutingV2.Result(
            flow,budget,Map.of(),Map.of(),Map.of(OUT,2.0/86400),
            rates,86400);
    }

    private static HydrologySeasonalLaggedLakeNetworkV2.Lake lake(
            double capacity,double previous,double evaporation,
            GlobalHydrologyAddressV2.Sample spill,int lag) {
        return new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            new LakeReservoirV2.Basin(new double[]{0},new double[]{1},capacity),
            previous,evaporation,spill,lag);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
            HydrologySeasonalLaggedLakeNetworkV2.Lake> chain(int lag) {
        return Map.of(A,lake(2,0,0,B,lag),B,lake(1,0,0,SPILL,1));
    }

    @Test void delayedCascadeConservesStoredInTransitAndTerminalWater() {
        var result=HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
            routed(9,0,-1),chain(1),3);
        assertEquals(2,result.lakes().get(A).volume(),1e-12);
        assertEquals(1,result.lakes().get(B).volume(),1e-12);
        assertEquals(4,result.lakeInflowM3().get(B),1e-12);
        assertEquals(3,result.pendingM3().get(B),1e-12);
        assertEquals(3,result.pendingM3().get(SPILL),1e-12);
        assertEquals(2,result.verifiedOutletM3(),1e-12);
        assertEquals(0,result.residualM3(),1e-10);
        assertEquals(11,result.seasonalRunoffM3()+result.previousLakeStorageM3(),1e-10);
        assertEquals(11,result.verifiedOutletM3()+
            result.pendingM3().values().stream().mapToDouble(Double::doubleValue).sum()+
            result.finalLakeStorageM3()+result.lakeEvaporationM3(),1e-10);
        assertEquals(3.0/86400,result.pendingM3PerSecond().get(B),1e-12);
    }

    @Test void travelDelayLongerThanSeasonPreservesUndeliveredSpill() {
        var result=HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
            routed(9,0,-1),chain(3),3);
        assertEquals(7,result.pendingM3().get(B),1e-12);
        assertFalse(result.pendingM3().containsKey(SPILL));
        assertEquals(0,result.lakeInflowM3().get(B),1e-12);
        assertEquals(0,result.residualM3(),1e-10);
    }

    @Test void inputMapOrderDoesNotChangeRoutingOrLakeIdentities() {
        var reverse=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
            HydrologySeasonalLaggedLakeNetworkV2.Lake>();
        reverse.put(B,chain(1).get(B));
        reverse.put(A,chain(1).get(A));
        assertEquals(HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(9,0,-1),chain(1),3),
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(9,0,-1),reverse,3));
    }

    @Test void twoUpstreamLakesConvergeWithoutDuplicateRunoff() {
        var lakes=Map.of(A,lake(2,0,0,B,1),
                         C,lake(1,0,0,B,1),
                         B,lake(3,0,0,SPILL,1));
        var result=HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
            routed(9,0,4),lakes,4);
        assertEquals(15,result.seasonalRunoffM3(),1e-12);
        assertEquals(0,result.residualM3(),1e-9);
        assertTrue(result.pendingM3().getOrDefault(B,0.0)>0);
        assertTrue(result.lakeInflowM3().get(B)>0);
    }

    @Test void chronologicalRainfallTimingChangesLakeStorage() {
        var lakes=Map.of(A,lake(5,0,2,SPILL,1));
        var early=Map.of(A,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(9,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,2)));
        var late=Map.of(A,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(0,2),
            new HydrologySeasonalLakeIntegratorV2.Interval(9,0)));
        var first=HydrologySeasonalLaggedLakeNetworkV2.advanceScheduled(
            routed(9,0,-1),lakes,early);
        var second=HydrologySeasonalLaggedLakeNetworkV2.advanceScheduled(
            routed(9,0,-1),lakes,late);
        assertEquals(3,first.lakes().get(A).volume(),1e-12);
        assertEquals(5,second.lakes().get(A).volume(),1e-12);
        assertEquals(0,first.residualM3(),1e-10);
        assertEquals(0,second.residualM3(),1e-10);
    }

    @Test void scheduledAndUniformForcingAgreeWhenIntervalsMatch() {
        var lakes=Map.of(A,lake(5,0,2,SPILL,1));
        var schedule=Map.of(A,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(4.5,1),
            new HydrologySeasonalLakeIntegratorV2.Interval(4.5,1)));
        assertEquals(HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
            routed(9,0,-1),lakes,2),
            HydrologySeasonalLaggedLakeNetworkV2.advanceScheduled(
            routed(9,0,-1),lakes,schedule));
    }

    @Test void rejectsSchedulesThatDoubleCountLocalOrUpstreamWater() {
        var lakes=Map.of(A,lake(5,0,2,SPILL,1));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceScheduled(
                routed(9,0,-1),lakes,Map.of(A,List.of(
                    new HydrologySeasonalLakeIntegratorV2.Interval(9,2),
                    new HydrologySeasonalLakeIntegratorV2.Interval(1,0)))));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceScheduled(
                routed(9,0,-1),lakes,Map.of(A,List.of(
                    new HydrologySeasonalLakeIntegratorV2.Interval(9,1),
                    new HydrologySeasonalLakeIntegratorV2.Interval(0,0)))));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceScheduled(
                routed(9,0,-1),lakes,Map.of()));
    }

    @Test void rejectsCyclicAndOrphanedLakeSpillRoutes() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(9,0,-1),
                Map.of(A,lake(2,0,0,B,1),B,lake(1,0,0,A,1)),3));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(9,0,-1),
                Map.of(sample(5,2),lake(1,0,0,OUT,1)),3));
    }

    @Test void rejectsCrossResolutionDuplicatePhysicalLakeInlets() {
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,2,2);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(9,0,-1),
                Map.of(A,lake(2,0,0,B,1),alias,lake(1,0,0,B,1)),3));
    }

    @Test void rejectsVerifiedMouthAsSpillDestination() {
        var mouth=sample(4,2);
        var nodes=List.of(new HydrologyFlowGraphV2.Node(B,9,null,false),
            new HydrologyFlowGraphV2.Node(mouth,2,null,true));
        var flow=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,flow);
        var routed=new HydrologySeasonalGlobalRoutingV2.Result(
            flow,budget,Map.of(),Map.of(),Map.of(mouth,2.0/86400),
            Map.of(B,9.0/86400),86400);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(routed,
                Map.of(B,lake(1,0,0,mouth,1)),3));
    }

    @Test void rejectsNonadjacentSpillsInvalidLagsAndOversizedSlices() {
        assertThrows(IllegalArgumentException.class,() ->
            lake(1,0,0,B,0));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(9,0,-1),Map.of(A,lake(1,0,0,OUT,1)),3));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(9,0,-1),chain(1),4097));
    }

    @Test void rejectsForgedMeanRatesBeforeRoutingLakes() {
        var original=routed(9,0,-1);
        var forged=new HydrologySeasonalGlobalRoutingV2.Result(
            original.drainage(),original.budget(),original.catchments(),
            original.reaches(),Map.of(OUT,999.0),
            original.frontierM3PerSecond(),original.durationSeconds());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                forged,chain(1),3));
    }

    @Test void preservesInputsAndReturnsImmutableDeterministicOutputs() {
        var before=routed(9,0,-1);
        var after=HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
            before,chain(1),3);
        assertEquals(9,before.drainage().pendingOutflow().get(A),0);
        assertThrows(UnsupportedOperationException.class,() ->
            after.pendingM3().put(A,100.0));
        assertThrows(UnsupportedOperationException.class,() ->
            after.lakes().clear());
        assertEquals(after,HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
            before,chain(1),3));
    }

    @Test void randomizedStorageAndEvaporationConserveAcrossLaggedSeasons() {
        var random=new Random(20261102);
        for (int i=0;i<200;i++) {
            double incoming=random.nextDouble()*90;
            double priorA=random.nextDouble()*2;
            double priorB=random.nextDouble()*3;
            double evaporation=random.nextDouble()*4;
            var lakes=Map.of(A,lake(2,priorA,evaporation,B,1),
                             B,lake(3,priorB,evaporation,SPILL,2));
            var result=HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(
                routed(incoming,0,-1),lakes,1+random.nextInt(32));
            assertEquals(0,result.residualM3(),1e-7);
            assertEquals(result.seasonalRunoffM3()+priorA+priorB,
                result.verifiedOutletM3()+
                result.pendingM3().values().stream().mapToDouble(Double::doubleValue).sum()+
                result.finalLakeStorageM3()+result.lakeEvaporationM3(),1e-7);
        }
    }

    @Test void emptyLakeInventoryPreservesPreviouslyVerifiedFrontier() {
        var before=routed(9,0,-1);
        assertEquals(HydrologySeasonalLakeRoutingV2.advance(before,Map.of()),
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniform(before,Map.of(),5));
        assertEquals(HydrologySeasonalLakeRoutingV2.advance(before,Map.of()),
            HydrologySeasonalLaggedLakeNetworkV2.advanceScheduled(
                before,Map.of(),Map.of()));
    }
}
