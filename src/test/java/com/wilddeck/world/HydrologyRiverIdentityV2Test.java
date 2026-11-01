package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverIdentityV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }

    @Test void outletAndReachIdsSurviveReorderingAndRainfallChanges(){
        var a=List.of(
            new HydrologyFlowGraphV2.Node(at(4),2,at(5),false),
            new HydrologyFlowGraphV2.Node(at(5),3,at(6),false),
            new HydrologyFlowGraphV2.Node(at(6),1,null,true));
        var b=List.of(
            new HydrologyFlowGraphV2.Node(at(6),9,null,true),
            new HydrologyFlowGraphV2.Node(at(5),7,at(6),false),
            new HydrologyFlowGraphV2.Node(at(4),8,at(5),false));
        var ra=HydrologyFlowGraphV2.solve(a);
        var rb=HydrologyFlowGraphV2.solve(b);
        assertEquals(HydrologyRiverIdentityV2.catchments(ra),
                     HydrologyRiverIdentityV2.catchments(rb));
        assertEquals(HydrologyRiverIdentityV2.resolvedReaches(a,ra),
                     HydrologyRiverIdentityV2.resolvedReaches(b,rb));
        assertEquals(new HydrologyRiverIdentityV2.CatchmentId(at(6)),
                     HydrologyRiverIdentityV2.catchments(ra).get(at(4)));
        assertEquals(2,HydrologyRiverIdentityV2.resolvedReaches(a,ra).size());
    }

    @Test void unresolvedFrontierNeverPretendsToBeConfirmedCatchment(){
        var partial=List.of(new HydrologyFlowGraphV2.Node(at(4),5,at(5),false));
        var graph=HydrologyFlowGraphV2.solve(partial);
        assertTrue(HydrologyRiverIdentityV2.catchments(graph).isEmpty());
        assertTrue(HydrologyRiverIdentityV2.resolvedReaches(partial,graph).isEmpty());
        assertEquals(5,graph.pendingOutflow().get(at(5)));
        var id=new HydrologyRiverIdentityV2.ReachId(at(4),at(5));
        assertEquals(id,new HydrologyRiverIdentityV2.ReachId(at(4),at(5)));
    }

    @Test void rejectsCatchmentIdentitiesFromDifferentGraphSolution(){
        var authoritative=List.of(
            new HydrologyFlowGraphV2.Node(at(4),2,at(5),false),
            new HydrologyFlowGraphV2.Node(at(5),3,at(6),false),
            new HydrologyFlowGraphV2.Node(at(6),1,null,true));
        var altered=List.of(
            new HydrologyFlowGraphV2.Node(at(4),2,at(5),false),
            new HydrologyFlowGraphV2.Node(at(5),3,null,true),
            new HydrologyFlowGraphV2.Node(at(6),1,null,true));
        var mismatched=HydrologyFlowGraphV2.solve(altered);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverIdentityV2.resolvedReaches(authoritative,mismatched));
        var correct=HydrologyFlowGraphV2.solve(authoritative);
        assertEquals(2,HydrologyRiverIdentityV2.resolvedReaches(authoritative,correct).size());
    }

    @Test void invalidReachCannotTeleportAcrossPlanet(){
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyRiverIdentityV2.ReachId(at(4),at(8)));
        assertThrows(NullPointerException.class,()->
            new HydrologyRiverIdentityV2.ReachId(at(4),null));
    }
}
