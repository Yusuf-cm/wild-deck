package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyStableReachV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }

    @Test void unloadedFrontierRetainsReachIdentityWhenOutletIsResolved(){
        var upstream=new HydrologyFlowGraphV2.Node(at(8),3,at(9),false);
        var first=HydrologyFlowGraphV2.solve(List.of(upstream));
        var reach=new HydrologyRiverIdentityV2.ReachId(at(8),at(9));
        assertEquals(Set.of(reach),HydrologyRiverIdentityV2.knownReaches(List.of(upstream)));
        assertTrue(HydrologyRiverIdentityV2.catchments(first).isEmpty());
        assertTrue(HydrologyRiverIdentityV2.resolvedReaches(List.of(upstream),first).isEmpty());

        var receiver=new HydrologyFlowGraphV2.Node(at(9),1,at(10),false);
        var outlet=new HydrologyFlowGraphV2.Node(at(10),0,null,true);
        var complete=List.of(outlet,upstream,receiver);
        var solved=HydrologyFlowGraphV2.solve(complete);
        assertTrue(HydrologyRiverIdentityV2.knownReaches(complete).contains(reach));
        var catchment=new HydrologyRiverIdentityV2.CatchmentId(at(10));
        assertEquals(catchment,HydrologyRiverIdentityV2.resolvedReaches(complete,solved).get(reach));
        assertEquals(4,solved.terminalOutflow().get(at(10)),1e-9);

        var wetter=List.of(outlet,
            new HydrologyFlowGraphV2.Node(at(8),9,at(9),false),receiver);
        assertEquals(HydrologyRiverIdentityV2.knownReaches(complete),
            HydrologyRiverIdentityV2.knownReaches(wetter));
        assertEquals(catchment,HydrologyRiverIdentityV2.resolvedReaches(
            wetter,HydrologyFlowGraphV2.solve(wetter)).get(reach));
    }

    @Test void duplicateGlobalSamplesCannotProduceAmbiguousReachIdentity(){
        var source=new HydrologyFlowGraphV2.Node(at(8),1,at(9),false);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverIdentityV2.knownReaches(List.of(source,source)));
    }
}
