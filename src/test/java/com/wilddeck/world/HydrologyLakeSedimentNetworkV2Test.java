package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeSedimentNetworkV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(1,2),
        B=at(2,2), C=at(3,2), D=at(4,2);

    private static HydrologySphericalLakeBasinsV2.Plan chain(double local) {
        var routes=new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(A,local,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,D,false),
            new HydrologyFlowGraphV2.Node(D,0,null,true)));
        for(long y=0;y<=4;y++)for(long x=0;x<(y==0||y==4?1:8);x++) {
            var address=at(x,y);
            if(!Set.of(A,B,C,D).contains(address))
                routes.add(new HydrologyFlowGraphV2.Node(address,0,null,true));
        }
        var basin=new LakeReservoirV2.Basin(new double[]{0},new double[]{1},5);
        return new HydrologySphericalLakeBasinsV2.Plan(routes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A,List.of(A),B,D,basin),
            new HydrologySphericalLakeBasinsV2.Lake(C,List.of(C),D,D,basin)));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
        HydrologyLakeNetworkStepV2.Forcing> forcing(double extra,double evap) {
        return Map.of(A,new HydrologyLakeNetworkStepV2.Forcing(extra,evap,1),
                      C,new HydrologyLakeNetworkStepV2.Forcing(0,0,1));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
        HydrologyLakeSedimentNetworkV2.Sediment> sediment(double mass) {
        return Map.of(A,new HydrologyLakeSedimentNetworkV2.Sediment(mass,2000),
                      C,new HydrologyLakeSedimentNetworkV2.Sediment(0,2000));
    }

    @Test void displacedWaterIsRoutedToDownstreamLakeExactlyOnce() {
        var result=HydrologyLakeSedimentNetworkV2.step(chain(0),
            new HydrologyLakeStateV2.State("world",Map.of(A,4.0)),
            forcing(0,0),sediment(4000),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(1,result.displaced().get(A),1e-12);
        assertEquals(3,result.updatedPlan().lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(3,result.water().state().volumes().get(A),1e-12);
        assertEquals(1,result.water().state().volumes().get(C),1e-12);
        assertEquals(1,result.water().lakeOverflow().get(A),1e-12);
        assertEquals(0,result.budget().terminalOutflow(),1e-12);
        assertEquals(0,result.budget().waterResidual(),1e-12);
        assertEquals(4000,result.budget().sedimentDeposited(),1e-8);
        assertEquals(0,result.budget().sedimentResidual(),1e-8);
    }

    @Test void sedimentDisplacementAndRiverInflowReachVerifiedTerminal() {
        var result=HydrologyLakeSedimentNetworkV2.step(chain(10),
            new HydrologyLakeStateV2.State("world",Map.of(A,4.0)),
            forcing(2,0),sediment(4000),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(8,result.budget().finalStorage(),1e-12);
        assertEquals(8,result.budget().terminalOutflow(),1e-12);
        assertEquals(10,result.budget().sourceWater(),1e-12);
        assertEquals(2,result.budget().externalInflow(),1e-12);
        assertEquals(0,result.budget().waterResidual(),1e-12);
        assertEquals(8,result.water().terminalOutflow().get(D),1e-12);
    }

    @Test void inflowFirstEvaporationConservesDisplacedWater() {
        var result=HydrologyLakeSedimentNetworkV2.step(chain(0),
            new HydrologyLakeStateV2.State("world",Map.of(A,4.0)),
            forcing(0,1),sediment(4000),
            SeasonalLakeEvolutionV2.EvaporationMode.INFLOW_THEN_EVAPORATION);
        assertEquals(1,result.budget().evaporation(),1e-12);
        assertEquals(3,result.budget().finalStorage(),1e-12);
        assertEquals(0,result.budget().waterResidual(),1e-12);
    }

    @Test void zeroSedimentMatchesExistingLakeRouting() {
        var plan=chain(6);
        var state=new HydrologyLakeStateV2.State("world",Map.of(A,2.0));
        var original=HydrologyLakeNetworkStepV2.step(plan,state,forcing(1,0),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        var coupled=HydrologyLakeSedimentNetworkV2.step(plan,state,forcing(1,0),
            sediment(0),SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(original,coupled.water());
        assertEquals(0,coupled.budget().waterResidual(),1e-12);
    }

    @Test void deterministicWhenLakeAndRouteEnumerationReversed() {
        var plan=chain(10);
        var routes=new ArrayList<>(plan.routes());
        var lakes=new ArrayList<>(plan.lakes());
        Collections.reverse(routes);
        Collections.reverse(lakes);
        var state=new HydrologyLakeStateV2.State("world",Map.of(A,4.0));
        var one=HydrologyLakeSedimentNetworkV2.step(plan,state,forcing(2,0),
            sediment(4000),SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        var two=HydrologyLakeSedimentNetworkV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(routes,lakes),state,
            forcing(2,0),sediment(4000),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(one.water(),two.water());
        assertEquals(one.displaced(),two.displaced());
        assertEquals(one.budget(),two.budget());
    }

    @Test void fullSiltingRequiresNewVerifiedTopologyWithoutMutatingInputs() {
        var plan=chain(0);
        var state=new HydrologyLakeStateV2.State("world",Map.of(A,4.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeSedimentNetworkV2.step(plan,state,forcing(0,0),
                sediment(10000),SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        assertEquals(5,plan.lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(4,state.volumes().get(A),1e-12);
    }

    @Test void rejectsMissingForcingAndInvalidSediment() {
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyLakeSedimentNetworkV2.Sediment(-1,2000));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyLakeSedimentNetworkV2.Sediment(1,0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeSedimentNetworkV2.step(chain(0),
                new HydrologyLakeStateV2.State("world",Map.of()),
                forcing(0,0),Map.of(),
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        assertThrows(NullPointerException.class,()->
            HydrologyLakeSedimentNetworkV2.step(chain(0),
                new HydrologyLakeStateV2.State("world",Map.of()),
                forcing(0,0),sediment(0),null));
    }
}
