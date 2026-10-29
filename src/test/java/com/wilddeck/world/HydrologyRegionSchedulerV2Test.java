package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRegionSchedulerV2Test {
    @Test void overlapsAreDeduplicatedAndOrderIsStable(){
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var a=new HydrologyRegionSchedulerV2.Region(0,0);
        var b=new HydrologyRegionSchedulerV2.Region(1,0);
        var loaded=HydrologyRegionSchedulerV2.load(List.of(b,a),1,12,r->{
            visited.add(r);
            return List.of();
        });
        assertEquals(12,loaded.size());
        assertEquals(12,new HashSet<>(visited).size());
        assertEquals(new HydrologyRegionSchedulerV2.Region(-1,-1),visited.get(0));
        assertEquals(new HydrologyRegionSchedulerV2.Region(2,1),visited.get(11));
        var sorted=new ArrayList<>(visited);
        Collections.sort(sorted);
        assertEquals(sorted,visited);
    }
    @Test void budgetFailsBeforeAnyProviderLoads(){
        var count=new int[1];
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRegionSchedulerV2.load(
                List.of(new HydrologyRegionSchedulerV2.Region(0,0)),2,20,r->{
                    count[0]++;
                    return List.of();
                }));
        assertEquals(0,count[0]);
    }
    @Test void pendingReceiptHintsRespectSharedVertexOwnership(){
        var inbox=new HydrologyEpochInboxV2();
        var a=new GlobalHydrologyAddressV2.Sample(2,4,3,4);
        var b=new GlobalHydrologyAddressV2.Sample(2,4,4,4);
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,a,b),1);
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(0,0)),
            HydrologyRegionSchedulerV2.pendingRegions(inbox.snapshot()));
        inbox.claimAvailable(1,Set.of(b));
        assertTrue(HydrologyRegionSchedulerV2.pendingRegions(inbox.snapshot()).isEmpty());
    }

    @Test void pendingHintsRejectMixedSamplingResolutions(){
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2,4,3,3),
            new GlobalHydrologyAddressV2.Sample(2,4,4,3)),1);
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(3,4,3,3),
            new GlobalHydrologyAddressV2.Sample(3,4,4,3)),1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRegionSchedulerV2.pendingRegions(inbox.snapshot()));
    }

    @Test void rejectsOverflowAndInvalidLimits(){
        assertThrows(ArithmeticException.class,()->
            HydrologyRegionSchedulerV2.load(
                List.of(new HydrologyRegionSchedulerV2.Region(Long.MAX_VALUE,0)),1,9,
                r->List.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRegionSchedulerV2.load(List.of(),-1,5,r->List.of()));
    }
}
