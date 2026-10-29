package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalSnowSoilCycleV2Test {
    private static SeasonalSnowSoilCycleV2.Cycle example() {
        var initial=new SeasonalSnowSoilCycleV2.State(
            new double[]{0,1},new double[]{0,0});
        var cold=new SeasonalSnowSoilCycleV2.Period(
            new double[]{5,2},new double[]{-5,2},new double[]{0,0},
            new double[]{2,1},1,0.5);
        var warm=new SeasonalSnowSoilCycleV2.Period(
            new double[]{1,0},new double[]{4,4},new double[]{1,0.5},
            new double[]{2,1},1,2);
        return SeasonalSnowSoilCycleV2.simulate(initial,List.of(cold,warm));
    }

    @Test void winterStorageAndSpringMeltConserveWaterPerCell() {
        var cycle=example();
        assertEquals(2,cycle.periods().size());
        assertArrayEquals(new double[]{5,0},cycle.periods().get(0).state().snowDepth(),1e-12);
        assertArrayEquals(new double[]{5,0},cycle.periods().get(1).meltDepth(),1e-12);
        assertArrayEquals(new double[]{0,0},cycle.finalState().snowDepth(),1e-12);
        assertArrayEquals(new double[]{2,0.5},cycle.finalState().soilDepth(),1e-12);
        assertArrayEquals(new double[]{3,2},cycle.totalRunoffDepth(),1e-12);
        assertArrayEquals(new double[]{1,0.5},cycle.totalEvaporationDepth(),1e-12);
        // Initial snow (1) + two periods' precipitation (8) = 9 m-equivalent.
        double initialAndRain=1+5+2+1;
        double output=Arrays.stream(cycle.finalState().snowDepth()).sum()+
            Arrays.stream(cycle.finalState().soilDepth()).sum()+
            Arrays.stream(cycle.totalRunoffDepth()).sum()+
            Arrays.stream(cycle.totalEvaporationDepth()).sum();
        assertEquals(initialAndRain,output,1e-12);
        assertArrayEquals(example().totalRunoffDepth(),cycle.totalRunoffDepth());
    }

    @Test void snowmeltRunoffCanEnterConservativeRiverGraphExactlyOnce() {
        var cycle=example();
        var runoff=cycle.totalRunoffDepth();
        var a=new GlobalHydrologyAddressV2.Sample(2,4,5,5);
        var b=new GlobalHydrologyAddressV2.Sample(2,4,6,5);
        var outlet=new GlobalHydrologyAddressV2.Sample(2,4,7,5);
        // 10 m2 cell area converts water depth (m) into volume (m3).
        var graph=List.of(
            new HydrologyFlowGraphV2.Node(a,runoff[0]*10,b,false),
            new HydrologyFlowGraphV2.Node(b,runoff[1]*10,outlet,false),
            new HydrologyFlowGraphV2.Node(outlet,0,null,true));
        var solved=HydrologyFlowGraphV2.solve(graph);
        assertEquals(50,solved.terminalOutflow().get(outlet),1e-10);
        assertEquals(0,HydrologyConservationV2.verify(graph,solved).residual(),1e-10);
        assertEquals(3,HydrologyRiverIdentityV2.catchments(solved).size());
    }

    @Test void inputAndOutputArraysAreDefensiveAndBadBudgetsFailClosed() {
        double[] snow=new double[]{2},soil=new double[]{0};
        var state=new SeasonalSnowSoilCycleV2.State(snow,soil);
        snow[0]=999;
        state.snowDepth()[0]=999;
        assertEquals(2,state.snowDepth()[0]);
        var rain=new double[]{1};
        var period=new SeasonalSnowSoilCycleV2.Period(
            rain,new double[]{3},new double[]{0},new double[]{1},1,1);
        rain[0]=999;
        assertEquals(1,period.precipitationDepth()[0]);
        var cycle=SeasonalSnowSoilCycleV2.simulate(state,List.of(period));
        cycle.totalRunoffDepth()[0]=999;
        assertNotEquals(999,cycle.totalRunoffDepth()[0]);
        assertThrows(IllegalArgumentException.class,()->
            new SeasonalSnowSoilCycleV2.State(new double[]{-1},new double[]{0}));
        assertThrows(IllegalArgumentException.class,()->
            new SeasonalSnowSoilCycleV2.Period(
                new double[]{1},new double[]{0},new double[]{0},
                new double[]{1},0,1));
        assertThrows(IllegalArgumentException.class,()->
            SeasonalSnowSoilCycleV2.simulate(
                new SeasonalSnowSoilCycleV2.State(
                    new double[]{Double.MAX_VALUE},new double[]{0}),
                List.of(new SeasonalSnowSoilCycleV2.Period(
                    new double[]{Double.MAX_VALUE},new double[]{-1},
                    new double[]{0},new double[]{1},1,1))));
        assertThrows(IllegalArgumentException.class,()->
            SeasonalSnowSoilCycleV2.simulate(state,List.of(
                new SeasonalSnowSoilCycleV2.Period(
                    new double[]{1,1},new double[]{1,1},new double[]{0,0},
                    new double[]{1,1},1,1))));
    }
}
