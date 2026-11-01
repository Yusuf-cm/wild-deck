package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyFrontierDispatchV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }

    @Test void independentConfluencesCrossUnloadedBoundaryExactlyOnce(){
        var destination=at(5,3);
        var a=List.of(
            new HydrologyFlowGraphV2.Node(at(4,3),2,destination,false),
            new HydrologyFlowGraphV2.Node(at(4,2),3,destination,false));
        var aResult=HydrologyFlowGraphV2.solve(a);
        assertEquals(5,aResult.pendingOutflow().get(destination));
        var dispatch=HydrologyFrontierDispatchV2.export(7,a,aResult);
        assertEquals(2,dispatch.transfers().size());
        assertTrue(dispatch.unresolved().isEmpty());
        var inbox=new HydrologyEpochInboxV2();
        assertEquals(2,dispatch.deliver(inbox));
        assertEquals(0,dispatch.deliver(inbox)); // duplicate delivery ignored
        var restored=HydrologyEpochInboxV2.restore(
            HydrologyInboxCodecV2.decode(HydrologyInboxCodecV2.encode(inbox.snapshot())));
        var b=List.of(
            new HydrologyFlowGraphV2.Node(destination,1,at(6,3),false),
            new HydrologyFlowGraphV2.Node(at(6,3),0,null,true));
        var result=HydrologyEpochSolverV2.solve(List.of(b),restored.claim(7));
        assertEquals(6,result.terminalOutflow().get(at(6,3)),1e-10);
        assertTrue(result.pendingOutflow().isEmpty());
        assertThrows(IllegalStateException.class,()->restored.claim(7));
    }

    @Test void unknownRouteIsRetainedRatherThanDelivered(){
        var source=at(4,3);
        var a=List.of(new HydrologyFlowGraphV2.Node(source,9,null,false));
        var dispatch=HydrologyFrontierDispatchV2.export(2,a,HydrologyFlowGraphV2.solve(a));
        assertTrue(dispatch.transfers().isEmpty());
        assertEquals(9,dispatch.unresolved().get(source));
    }

    @Test void rejectsResultsFromDifferentGraph(){
        var a=List.of(new HydrologyFlowGraphV2.Node(at(4,3),9,at(5,3),false));
        var b=List.of(new HydrologyFlowGraphV2.Node(at(4,3),2,at(5,3),false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyFrontierDispatchV2.export(1,a,HydrologyFlowGraphV2.solve(b)));
    }

    @Test void sharedSampleIsCountedOnlyOnceBeforeDispatch(){
        var shared=new HydrologyFlowGraphV2.Node(at(4,3),2,at(5,3),false);
        var left=List.of(shared);
        var right=List.of(shared);
        var merged=HydrologyMosaicJoinV2.merge(List.of(left,right));
        var dispatch=HydrologyFrontierDispatchV2.export(3,merged,
            HydrologyFlowGraphV2.solve(merged));
        assertEquals(1,dispatch.transfers().size());
        assertEquals(2,dispatch.transfers().get(0).flow());
    }
    @Test void dryKnownFrontiersDoNotCreateDurableReceipts() {
        var destination=at(5,3);
        var nodes=List.of(
            new HydrologyFlowGraphV2.Node(at(4,3),0,destination,false),
            new HydrologyFlowGraphV2.Node(at(4,2),3,destination,false));
        var solved=HydrologyFlowGraphV2.solve(nodes);
        var dispatch=HydrologyFrontierDispatchV2.export(4,nodes,solved);
        assertEquals(1,dispatch.transfers().size());
        assertEquals(3,dispatch.transfers().get(0).flow(),1e-12);
        var inbox=new HydrologyEpochInboxV2();
        assertEquals(1,dispatch.deliver(inbox));
        assertEquals(1,inbox.snapshot().receipts().size());
        assertEquals(0,dispatch.deliver(inbox));
        var allDry=List.of(new HydrologyFlowGraphV2.Node(
            at(4,3),0,destination,false));
        var dry=HydrologyFrontierDispatchV2.export(
            5,allDry,HydrologyFlowGraphV2.solve(allDry));
        assertTrue(dry.transfers().isEmpty());
        assertEquals(0,dry.deliver(inbox));
        assertEquals(1,inbox.snapshot().receipts().size());
        // Unknown routes still remain explicitly unresolved even when dry.
        var unknown=List.of(new HydrologyFlowGraphV2.Node(
            at(4,3),0,null,false));
        assertTrue(HydrologyFrontierDispatchV2.export(
            5,unknown,HydrologyFlowGraphV2.solve(unknown))
            .unresolved().containsKey(at(4,3)));
    }

}
