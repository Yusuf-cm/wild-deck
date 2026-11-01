package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeSedimentSeasonsV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(1,2),
        B=at(2,2), C=at(3,2), D=at(4,2);
    private static HydrologySphericalLakeBasinsV2.Plan plan() {
        var nodes=new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,D,false),
            new HydrologyFlowGraphV2.Node(D,0,null,true)));
        for(long y=0;y<=4;y++)for(long x=0;x<(y==0||y==4?1:8);x++) {
            var s=at(x,y);
            if(!Set.of(A,B,C,D).contains(s))
                nodes.add(new HydrologyFlowGraphV2.Node(s,0,null,true));
        }
        var basin=new LakeReservoirV2.Basin(new double[]{0},new double[]{1},5);
        return new HydrologySphericalLakeBasinsV2.Plan(nodes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A,List.of(A),B,D,basin),
            new HydrologySphericalLakeBasinsV2.Lake(C,List.of(C),D,D,basin)));
    }
    private static HydrologyLakeSedimentSeasonsV2.Season season(double rain,double sediment) {
        return new HydrologyLakeSedimentSeasonsV2.Season(Map.of(A,rain),
            Map.of(A,new HydrologyLakeNetworkStepV2.Forcing(0,0,1),
                   C,new HydrologyLakeNetworkStepV2.Forcing(0,0,1)),
            Map.of(A,new HydrologyLakeSedimentNetworkV2.Sediment(sediment,2000),
                   C,new HydrologyLakeSedimentNetworkV2.Sediment(0,2000)));
    }
    @Test void sedimentGeometryAndWaterPersistAcrossTwoSeasonSteps() {
        var steps=HydrologyLakeSedimentSeasonsV2.simulate(plan(),
            new HydrologyLakeStateV2.State("world",Map.of(A,4.0)),
            List.of(season(0,4000),season(10,0)),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(2,steps.size());
        assertEquals(3,steps.get(0).updatedPlan().lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(3,steps.get(1).updatedPlan().lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(4,steps.get(0).budget().finalStorage(),1e-12);
        assertEquals(8,steps.get(1).budget().finalStorage(),1e-12);
        assertEquals(6,steps.get(1).budget().terminalOutflow(),1e-12);
        assertEquals(0,steps.get(1).budget().waterResidual(),1e-12);
        assertEquals(10,steps.get(1).budget().sourceWater(),1e-12);
    }
    @Test void seasonOrderingIsDeterministicAndInputIsUnchanged() {
        var base=plan();
        var reversed=new ArrayList<>(base.routes());
        Collections.reverse(reversed);
        var original=new HydrologyLakeStateV2.State("world",Map.of(A,4.0));
        var weather=List.of(season(0,4000),season(10,0));
        var first=HydrologyLakeSedimentSeasonsV2.simulate(base,original,weather,
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        var second=HydrologyLakeSedimentSeasonsV2.simulate(
            new HydrologySphericalLakeBasinsV2.Plan(reversed,base.lakes()),
            original,weather,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(first.get(1).water(),second.get(1).water());
        assertEquals(first.get(1).budget(),second.get(1).budget());
        assertEquals(5,base.lakes().get(0).reservoir().capacity(),1e-12);
        assertEquals(4,original.volumes().get(A),1e-12);
    }
    @Test void refusesRepeatedOrUnknownSourcesAndBadRunoff() {
        var base=plan();
        var nonzero=new ArrayList<>(base.routes());
        nonzero.set(0,new HydrologyFlowGraphV2.Node(A,1,B,false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeSedimentSeasonsV2.simulate(
                new HydrologySphericalLakeBasinsV2.Plan(nonzero,base.lakes()),
                new HydrologyLakeStateV2.State("world",Map.of()),List.of(),
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        var unknown=new HydrologyLakeSedimentSeasonsV2.Season(
            Map.of(at(8,2),1.0),season(0,0).water(),season(0,0).sediment());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeSedimentSeasonsV2.simulate(base,
                new HydrologyLakeStateV2.State("world",Map.of()),List.of(unknown),
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        var negative=new HydrologyLakeSedimentSeasonsV2.Season(
            Map.of(A,-1.0),season(0,0).water(),season(0,0).sediment());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeSedimentSeasonsV2.simulate(base,
                new HydrologyLakeStateV2.State("world",Map.of()),List.of(negative),
                SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
    }
}
