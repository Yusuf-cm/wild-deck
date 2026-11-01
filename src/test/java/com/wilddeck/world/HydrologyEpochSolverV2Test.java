package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyEpochSolverV2Test {
    private static GlobalHydrologyAddressV2.Sample sample(long x){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    @Test void mergesOverlappingTilesAndConservesWater(){
        var left=List.of(
            new HydrologyFlowGraphV2.Node(sample(4),2,sample(5),false),
            new HydrologyFlowGraphV2.Node(sample(5),3,sample(6),false));
        var right=List.of(
            new HydrologyFlowGraphV2.Node(sample(5),3,sample(6),false),
            new HydrologyFlowGraphV2.Node(sample(6),4,null,true));
        var result=HydrologyEpochSolverV2.solve(List.of(left,right),Map.of());
        assertEquals(9,result.terminalOutflow().get(sample(6)),1e-9);
        assertTrue(result.pendingOutflow().isEmpty());
    }
    @Test void unresolvedWaterIsRetainedAtFrontier(){
        var source=List.of(new HydrologyFlowGraphV2.Node(sample(4),3,sample(5),false));
        var result=HydrologyEpochSolverV2.solve(List.of(source),Map.of());
        assertEquals(3,result.pendingOutflow().get(sample(5)),1e-9);
    }
    @Test void incomingFromPreviousEpochIsCountedOnce(){
        var nodes=List.of(new HydrologyFlowGraphV2.Node(sample(5),2,null,true));
        var result=HydrologyEpochSolverV2.solve(List.of(nodes),Map.of(sample(5),7.0));
        assertEquals(9,result.terminalOutflow().get(sample(5)),1e-9);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyEpochSolverV2.solve(List.of(nodes),Map.of(sample(6),7.0)));
    }
    @Test void rejectsCycles(){
        var nodes=List.of(new HydrologyFlowGraphV2.Node(sample(4),1,sample(5),false),
                          new HydrologyFlowGraphV2.Node(sample(5),1,sample(4),false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyEpochSolverV2.solve(List.of(nodes),Map.of()));
    }
}
