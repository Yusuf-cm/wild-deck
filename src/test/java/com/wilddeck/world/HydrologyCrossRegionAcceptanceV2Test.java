package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-region acceptance: shared source vertices are counted once, known
 * frontier edges can be delivered once, and confirmed outlet identities
 * remain anchored when a downstream region is discovered.
 */
class HydrologyCrossRegionAcceptanceV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }

    @Test void sharedVertexConfluenceConservesWaterAndConfirmsStableOutlet(){
        var upstream=List.of(
            new HydrologyFlowGraphV2.Node(at(4,3),2,at(5,3),false),
            new HydrologyFlowGraphV2.Node(at(4,4),3,at(5,3),false));
        var overlappingReceiver=List.of(
            new HydrologyFlowGraphV2.Node(at(4,3),2,null,false),
            new HydrologyFlowGraphV2.Node(at(5,3),1,at(6,3),false),
            new HydrologyFlowGraphV2.Node(at(6,3),0,null,true));
        var merged=HydrologyMosaicJoinV2.merge(List.of(overlappingReceiver,upstream));
        assertEquals(4,merged.size());
        var complete=HydrologyFlowGraphV2.solve(merged);
        assertEquals(6,complete.terminalOutflow().get(at(6,3)),1e-9);
        assertTrue(complete.pendingOutflow().isEmpty());
        var ids=HydrologyRiverIdentityV2.catchments(complete);
        var outlet=new HydrologyRiverIdentityV2.CatchmentId(at(6,3));
        assertEquals(outlet,ids.get(at(4,3)));
        assertEquals(outlet,ids.get(at(4,4)));
        assertEquals(outlet,ids.get(at(5,3)));
        assertEquals(3,HydrologyRiverIdentityV2.resolvedReaches(merged,complete).size());
        var reordered=HydrologyMosaicJoinV2.merge(List.of(upstream,overlappingReceiver));
        assertEquals(complete,HydrologyFlowGraphV2.solve(reordered));
    }

    @Test void unloadedBoundaryReplaysCannotDoubleCountConfluence(){
        var upstream=List.of(
            new HydrologyFlowGraphV2.Node(at(4,3),2,at(5,3),false),
            new HydrologyFlowGraphV2.Node(at(4,4),3,at(5,3),false));
        var partial=HydrologyFlowGraphV2.solve(upstream);
        assertTrue(HydrologyRiverIdentityV2.catchments(partial).isEmpty());
        assertEquals(5,partial.pendingOutflow().get(at(5,3)),1e-9);
        var export=HydrologyFrontierDispatchV2.export(7,upstream,partial);
        var inbox=new HydrologyEpochInboxV2();
        assertEquals(2,export.deliver(inbox));
        assertEquals(0,export.deliver(inbox));
        var receiver=List.of(
            new HydrologyFlowGraphV2.Node(at(5,3),1,at(6,3),false),
            new HydrologyFlowGraphV2.Node(at(6,3),0,null,true));
        var resolved=HydrologyEpochSolverV2.solve(List.of(receiver),inbox.claim(7));
        assertEquals(6,resolved.terminalOutflow().get(at(6,3)),1e-9);
        assertThrows(IllegalStateException.class,()->inbox.claim(7));
        assertEquals(new HydrologyRiverIdentityV2.CatchmentId(at(6,3)),
            HydrologyRiverIdentityV2.catchments(resolved).get(at(5,3)));
    }
}
