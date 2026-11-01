package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMosaicJoinV2Test {
    private static GlobalHydrologyAddressV2.Sample at(int x) {
        return new GlobalHydrologyAddressV2.Sample(2,2,x,2);
    }
    @Test void overlappingTilesCountSharedRainOnceAndContinueRouting() {
        var a=at(1);var shared=at(2);var b=at(3);
        var left=List.of(new HydrologyFlowGraphV2.Node(a,2,shared,false),
                         new HydrologyFlowGraphV2.Node(shared,3,null,false));
        var right=List.of(new HydrologyFlowGraphV2.Node(shared,3,b,false),
                          new HydrologyFlowGraphV2.Node(b,4,null,true));
        var joined=HydrologyMosaicJoinV2.merge(List.of(left,right));
        assertEquals(3,joined.size());
        assertEquals(joined,HydrologyMosaicJoinV2.merge(List.of(right,left)));
        var solved=HydrologyFlowGraphV2.solve(joined);
        assertEquals(9,solved.terminalOutflow().get(b));
        assertTrue(solved.pendingOutflow().isEmpty());
        assertEquals(b,solved.resolvedCatchments().get(a));
    }
    @Test void acceptsRainfallOwnedOnlyByOneTile() {
        var shared=at(2);
        var first=List.of(new HydrologyFlowGraphV2.Node(shared,3,null,false));
        var second=List.of(new HydrologyFlowGraphV2.Node(shared,0,null,true));
        var solved=HydrologyFlowGraphV2.solve(HydrologyMosaicJoinV2.merge(List.of(first,second)));
        assertEquals(3,solved.terminalOutflow().get(shared));
    }
    @Test void rejectsIncompatibleSharedRainfallAndRoutes() {
        var shared=at(2);var a=at(1);var b=at(3);
        assertThrows(IllegalArgumentException.class,()->HydrologyMosaicJoinV2.merge(List.of(
            List.of(new HydrologyFlowGraphV2.Node(shared,2,null,false)),
            List.of(new HydrologyFlowGraphV2.Node(shared,3,null,false)))));
        assertThrows(IllegalArgumentException.class,()->HydrologyMosaicJoinV2.merge(List.of(
            List.of(new HydrologyFlowGraphV2.Node(shared,2,a,false)),
            List.of(new HydrologyFlowGraphV2.Node(shared,2,b,false)))));
        assertThrows(IllegalArgumentException.class,()->HydrologyMosaicJoinV2.merge(List.of(
            List.of(new HydrologyFlowGraphV2.Node(shared,2,null,true)),
            List.of(new HydrologyFlowGraphV2.Node(shared,2,b,false)))));
    }
}
