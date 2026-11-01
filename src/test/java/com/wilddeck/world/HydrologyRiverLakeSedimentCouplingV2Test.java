package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverLakeSedimentCouplingV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        FROM=at(0,2), A=at(1,2), B=at(2,2), C=at(3,2), D=at(4,2),
        SECOND=at(1,3);

    private static HydrologySphericalLakeBasinsV2.Plan plan() {
        var routes=new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(FROM,0,A,false),
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,D,false),
            new HydrologyFlowGraphV2.Node(D,0,null,true)));
        for (long y=0;y<=4;y++)
            for (long x=0;x<(y==0||y==4?1:8);x++) {
                var sample=at(x,y);
                if (!Set.of(FROM,A,B,C,D).contains(sample))
                    routes.add(new HydrologyFlowGraphV2.Node(sample,0,null,true));
            }
        return new HydrologySphericalLakeBasinsV2.Plan(routes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A,List.of(A),B,D,
                new LakeReservoirV2.Basin(new double[]{0},new double[]{1},5))));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> masses(
            HydrologySphericalLakeBasinsV2.Plan plan,double upstream,double below) {
        var result=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:plan.routes()) result.put(node.address(),0.0);
        result.put(FROM,upstream);
        result.put(B,below);
        return result;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> capacity(
            HydrologySphericalLakeBasinsV2.Plan plan) {
        var result=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:plan.routes()) result.put(node.address(),100_000.0);
        return result;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water() {
        return Map.of(A,new HydrologyLakeNetworkStepV2.Forcing(0,0,1));
    }

    @Test void riverErosionEntersLakeOnceAndUntrappedMassReachesOutlet() {
        var plan=plan();
        var routed=HydrologyRiverSedimentTransportV2.route(
            plan,masses(plan,4000,100),capacity(plan));
        assertEquals(4000,routed.lakeCaptured().get(A),1e-12);
        assertEquals(100,routed.terminalExport().get(D),1e-12);
        assertEquals(0,routed.budget().residual(),1e-12);
        assertEquals(4100,routed.budget().eroded(),1e-12);
        assertEquals(0,routed.budget().riverDeposited(),1e-12);
        assertTrue(routed.pendingExport().isEmpty());
        var coupled=HydrologyRiverLakeSedimentCouplingV2.step(plan,
            new HydrologyLakeStateV2.State("initial",Map.of(A,4.0)),
            water(),masses(plan,4000,100),capacity(plan),2000,
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(3,coupled.lake().updatedPlan().lakes().get(0)
            .reservoir().capacity(),1e-12);
        assertEquals(1,coupled.lake().displaced().get(A),1e-12);
        assertEquals(1,coupled.lake().budget().terminalOutflow(),1e-12);
        assertEquals(3,coupled.lake().budget().finalStorage(),1e-12);
        assertEquals(4000,coupled.budget().lakeDeposited(),1e-12);
        assertEquals(100,coupled.budget().terminalExported(),1e-12);
        assertEquals(0,coupled.budget().residual(),1e-12);
    }

    @Test void transportCapacityDepositsRiverSedimentBeforeLake() {
        var plan=plan();
        var capacities=capacity(plan());
        capacities.put(FROM,3.0);
        var routed=HydrologyRiverSedimentTransportV2.route(
            plan,masses(plan,9,0),capacities);
        assertEquals(6,routed.riverDeposited().get(FROM),1e-12);
        assertEquals(3,routed.lakeCaptured().get(A),1e-12);
        assertEquals(0,routed.budget().residual(),1e-12);
    }

    @Test void twoUpstreamBranchesConvergeAtSingleLakeWithoutDuplicateSource() {
        var original=plan();
        var routes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for (var node:original.routes())
            routes.add(node.address().equals(SECOND)
                ? new HydrologyFlowGraphV2.Node(SECOND,0,A,false) : node);
        var joined=new HydrologySphericalLakeBasinsV2.Plan(routes,original.lakes());
        var mass=masses(joined,2,0);
        mass.put(SECOND,5.0);
        var result=HydrologyRiverSedimentTransportV2.route(joined,mass,capacity(joined));
        assertEquals(7,result.lakeCaptured().get(A),1e-12);
        assertEquals(7,result.budget().eroded(),1e-12);
        assertEquals(0,result.budget().residual(),1e-12);
    }

    @Test void unloadedNeighborRetainsPendingSedimentAtDestination() {
        var partial=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(FROM,0,A,false)),List.of());
        var result=HydrologyRiverSedimentTransportV2.route(
            partial,Map.of(FROM,5.0),Map.of(FROM,10.0));
        assertEquals(5,result.pendingExport().get(A),1e-12);
        assertEquals(5,result.budget().pendingExported(),1e-12);
        assertEquals(0,result.budget().residual(),1e-12);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverLakeSedimentCouplingV2.step(partial,
                new HydrologyLakeStateV2.State("",Map.of()),Map.of(),
                Map.of(FROM,5.0),Map.of(FROM,10.0),2000,
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
    }

    @Test void routingIsIndependentOfSourceAndMapIterationOrder() {
        var base=plan();
        var reverse=new ArrayList<>(base.routes());
        Collections.reverse(reverse);
        var reversed=new HydrologySphericalLakeBasinsV2.Plan(reverse,base.lakes());
        var mass=masses(base,4000,100);
        var reordered=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        var keys=new ArrayList<>(mass.keySet());
        Collections.reverse(keys);
        for (var key:keys) reordered.put(key,mass.get(key));
        var first=HydrologyRiverSedimentTransportV2.route(base,mass,capacity(base));
        var second=HydrologyRiverSedimentTransportV2.route(
            reversed,reordered,capacity(reversed));
        assertEquals(first,second);
    }

    @Test void duplicateSourcesInvalidInputsAndCyclesFailClosed() {
        var base=plan();
        var duplicated=new ArrayList<>(base.routes());
        duplicated.add(duplicated.get(0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.route(
                new HydrologySphericalLakeBasinsV2.Plan(duplicated,base.lakes()),
                masses(base,1,0),capacity(base)));
        var incomplete=masses(base,1,0);
        incomplete.remove(FROM);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.route(base,incomplete,capacity(base)));
        var invalid=masses(base,1,0);
        invalid.put(FROM,Double.NaN);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.route(base,invalid,capacity(base)));
        var capacities=capacity(base);
        capacities.put(A,-1.0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.route(base,masses(base,1,0),capacities));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverLakeSedimentCouplingV2.step(base,
                new HydrologyLakeStateV2.State("",Map.of()),water(),
                masses(base,1,0),capacity(base),0,
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
    }

    @Test void cyclicRiverRoutingAndOverlappingLakeCellsAreRejected() {
        var cyclic=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(FROM,0,A,false),
            new HydrologyFlowGraphV2.Node(A,0,FROM,false)),List.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.route(cyclic,
                Map.of(FROM,1.0,A,0.0),Map.of(FROM,10.0,A,10.0)));
        var original=plan();
        var duplicateLake=new HydrologySphericalLakeBasinsV2.Lake(B,
            List.of(B,A),C,D,new LakeReservoirV2.Basin(
                new double[]{0,0},new double[]{1,1},5));
        var overlapping=new HydrologySphericalLakeBasinsV2.Plan(
            original.routes(),List.of(original.lakes().get(0),duplicateLake));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.route(overlapping,
                masses(original,1,0),capacity(original)));
    }

    @Test void seededSedimentMassSweepConservesAndReordersExactly() {
        var base=plan();
        var reversed=new ArrayList<>(base.routes());
        Collections.reverse(reversed);
        var other=new HydrologySphericalLakeBasinsV2.Plan(reversed,base.lakes());
        var random=new Random(41792);
        for (int trial=0;trial<200;trial++) {
            var mass=masses(base,random.nextInt(5000),random.nextInt(1000));
            var caps=capacity(base);
            caps.put(FROM,(double)random.nextInt(5000));
            caps.put(B,(double)random.nextInt(1000));
            var a=HydrologyRiverSedimentTransportV2.route(base,mass,caps);
            var b=HydrologyRiverSedimentTransportV2.route(other,mass,caps);
            assertEquals(a,b,"Sediment routing depends on enumeration at trial "+trial);
            assertEquals(0,a.budget().residual(),1e-10);
            assertEquals(a.budget().eroded(),
                a.budget().riverDeposited()+a.budget().lakeCaptured()+
                a.budget().terminalExported()+a.budget().pendingExported(),1e-8);
        }
    }

    @Test void completeLakeSiltingRefusesUnverifiedDrainageAndPreservesInputs() {
        var base=plan();
        var original=new HydrologyLakeStateV2.State("unchanged",Map.of(A,4.0));
        var eroded=masses(base,10000,0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverLakeSedimentCouplingV2.step(base,original,water(),
                eroded,capacity(base),2000,
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        assertEquals(4,original.volumes().get(A),1e-12);
        assertEquals(5,base.lakes().get(0).reservoir().capacity(),1e-12);
    }

    @Test void importedRiverSedimentFillsLakeOnceAndDisplacedWaterConserves() {
        var base=plan();
        var state=new HydrologyLakeStateV2.State("initial",Map.of(A,4.0));
        var received=HydrologyRiverLakeSedimentCouplingV2.stepWithIncoming(
            base,state,water(),masses(base,0,0),capacity(base),
            Map.of(FROM,4000.0,B,100.0),2000,
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(0,received.localEroded(),1e-12);
        assertEquals(4100,received.imported(),1e-12);
        assertEquals(4100,received.result().budget().eroded(),1e-12);
        assertEquals(4000,received.result().budget().lakeDeposited(),1e-12);
        assertEquals(100,received.result().budget().terminalExported(),1e-12);
        assertEquals(1,received.result().lake().displaced().get(A),1e-12);
        assertEquals(3,received.result().lake().budget().finalStorage(),1e-12);
        assertEquals(1,received.result().lake().budget().terminalOutflow(),1e-12);
        assertEquals(0,received.result().budget().residual(),1e-12);
        assertEquals(Map.of(A,4.0),state.volumes());
    }

    @Test void directLakeImportIsCapturedWithoutDoubleForwarding() {
        var base=plan();
        var result=HydrologyRiverLakeSedimentCouplingV2.stepWithIncoming(
            base,new HydrologyLakeStateV2.State("initial",Map.of(A,4.0)),
            water(),masses(base,0,0),capacity(base),Map.of(A,1000.0),
            2000,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(1000,result.result().river().lakeCaptured().get(A),1e-12);
        assertEquals(1000,result.result().budget().lakeDeposited(),1e-12);
        assertEquals(0,result.result().budget().terminalExported(),1e-12);
        assertEquals(0,result.result().budget().residual(),1e-12);
    }

    @Test void invalidSedimentImportsFailBeforeChangingLakeStorage() {
        var base=plan();
        var state=new HydrologyLakeStateV2.State("initial",Map.of(A,4.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverLakeSedimentCouplingV2.stepWithIncoming(
                base,state,water(),masses(base,0,0),capacity(base),
                Map.of(at(8,2),1.0),2000,
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverLakeSedimentCouplingV2.stepWithIncoming(
                base,state,water(),masses(base,0,0),capacity(base),
                Map.of(FROM,Double.POSITIVE_INFINITY),2000,
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        assertEquals(4,state.volumes().get(A),1e-12);
    }

    @Test void importedSedimentIsDeterministicAcrossRouteAndMapOrdering() {
        var base=plan();
        var reversed=new ArrayList<>(base.routes());
        Collections.reverse(reversed);
        var other=new HydrologySphericalLakeBasinsV2.Plan(reversed,base.lakes());
        var state=new HydrologyLakeStateV2.State("initial",Map.of(A,4.0));
        var imports=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        imports.put(B,100.0);
        imports.put(FROM,4000.0);
        var first=HydrologyRiverLakeSedimentCouplingV2.stepWithIncoming(
            base,state,water(),masses(base,0,0),capacity(base),
            Map.of(FROM,4000.0,B,100.0),2000,
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        var second=HydrologyRiverLakeSedimentCouplingV2.stepWithIncoming(
            other,state,water(),masses(base,0,0),capacity(base),
            imports,2000,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(first,second);
    }
}
