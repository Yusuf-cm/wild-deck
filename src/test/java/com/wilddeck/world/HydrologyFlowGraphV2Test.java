package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyFlowGraphV2Test {
    private static GlobalHydrologyAddressV2.Sample sample(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(3,4,x,y);
    }
    @Test void unloadedTargetRetainsWaterUntilItIsLoaded() {
        var a=sample(4,4);var b=sample(5,4);var c=sample(6,4);
        var first=HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(b,3,c,false),
            new HydrologyFlowGraphV2.Node(a,2,b,false)));
        assertEquals(2,first.discharge().get(a));
        assertEquals(5,first.discharge().get(b));
        assertEquals(5,first.pendingOutflow().get(c));
        assertTrue(first.terminalOutflow().isEmpty());
        assertTrue(first.resolvedCatchments().isEmpty());

        var loaded=HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(c,4,null,true),
            new HydrologyFlowGraphV2.Node(a,2,b,false),
            new HydrologyFlowGraphV2.Node(b,3,c,false)));
        assertTrue(loaded.pendingOutflow().isEmpty());
        assertEquals(9,loaded.terminalOutflow().get(c));
        assertEquals(c,loaded.resolvedCatchments().get(a));
        assertEquals(c,loaded.resolvedCatchments().get(b));
        assertEquals(c,loaded.resolvedCatchments().get(c));
    }
    @Test void confluencesAreConservativeAndOrderIndependent() {
        var a=sample(4,4);var b=sample(5,3);var c=sample(5,4);
        var nodes=List.of(new HydrologyFlowGraphV2.Node(a,7,c,false),
                          new HydrologyFlowGraphV2.Node(b,11,c,false),
                          new HydrologyFlowGraphV2.Node(c,3,null,true));
        var first=HydrologyFlowGraphV2.solve(nodes);
        var shuffled=new ArrayList<>(nodes);
        Collections.reverse(shuffled);
        var second=HydrologyFlowGraphV2.solve(shuffled);
        assertEquals(first,second);
        assertEquals(21,first.terminalOutflow().get(c));
    }
    @Test void rejectsCyclesAndDuplicateRainfallSources() {
        var a=sample(1,1);var b=sample(2,1);
        assertThrows(IllegalArgumentException.class,()->HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(a,1,b,false),
            new HydrologyFlowGraphV2.Node(b,2,a,false))));
        assertThrows(IllegalArgumentException.class,()->HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(a,1,null,false),
            new HydrologyFlowGraphV2.Node(a,1,null,false))));
    }
    @Test void unresolvedLocalBoundaryIsNotMisidentifiedAsCatchment() {
        var a=sample(1,1);
        var result=HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(a,5,null,false)));
        assertEquals(5,result.pendingOutflow().get(a));
        assertTrue(result.resolvedCatchments().isEmpty());
    }
    @Test void rejectsMixedResolutionsAndInvalidWater() {
        var a=sample(1,1);
        var other=new GlobalHydrologyAddressV2.Sample(4,4,1,1);
        assertThrows(IllegalArgumentException.class,()->new HydrologyFlowGraphV2.Node(a,-1,null,true));
        assertThrows(IllegalArgumentException.class,()->HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(a,1,null,true),
            new HydrologyFlowGraphV2.Node(other,1,null,true))));
    }
}
