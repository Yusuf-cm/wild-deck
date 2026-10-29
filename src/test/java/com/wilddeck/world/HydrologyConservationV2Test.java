package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyConservationV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }

    @Test void confluenceAndUnloadedBoundaryConserveWaterWithoutCountingInternalFlow() {
        var a=at(4,4);var b=at(4,5);var join=at(5,4);var outlet=at(6,4);
        var nodes=List.of(
            new HydrologyFlowGraphV2.Node(a,7,join,false),
            new HydrologyFlowGraphV2.Node(b,11,join,false),
            new HydrologyFlowGraphV2.Node(join,3,outlet,false));
        var solved=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,solved);
        assertEquals(21,budget.input(),1e-12);
        assertEquals(0,budget.outlet(),1e-12);
        assertEquals(21,budget.frontier(),1e-12);
        assertEquals(0,budget.residual(),1e-12);
        var reversed=new ArrayList<>(nodes);
        Collections.reverse(reversed);
        assertEquals(budget,HydrologyConservationV2.verify(reversed,solved));
    }

    @Test void mixedTerminalAndPendingOutletsConserveOnlySourceWater() {
        var a=at(2,2);var b=at(5,2);
        var nodes=List.of(
            new HydrologyFlowGraphV2.Node(a,4,null,true),
            new HydrologyFlowGraphV2.Node(b,6,at(6,2),false));
        var budget=HydrologyConservationV2.verify(nodes,HydrologyFlowGraphV2.solve(nodes));
        assertEquals(10,budget.input(),1e-12);
        assertEquals(4,budget.outlet(),1e-12);
        assertEquals(6,budget.frontier(),1e-12);
    }

    @Test void rejectsLossAndDoubleCountingAndDifferentSourceSets() {
        var a=at(2,2);
        var nodes=List.of(new HydrologyFlowGraphV2.Node(a,5,null,true));
        var solved=HydrologyFlowGraphV2.solve(nodes);
        var lost=new HydrologyFlowGraphV2.Result(
            solved.discharge(),Map.of(a,4.0),Map.of(),Map.of(a,a));
        var duplicated=new HydrologyFlowGraphV2.Result(
            solved.discharge(),Map.of(a,5.0),Map.of(a,5.0),Map.of(a,a));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyConservationV2.verify(nodes,lost));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyConservationV2.verify(nodes,duplicated));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyConservationV2.verify(List.of(nodes.get(0),nodes.get(0)),solved));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyConservationV2.verify(List.of(
                new HydrologyFlowGraphV2.Node(at(3,2),5,null,true)),solved));
    }
}
