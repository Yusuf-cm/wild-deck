package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyReceiptBudgetV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }

    @Test void parkedConfluenceBecomesDeliveredOnceAcrossSerialization() {
        var inbox=new HydrologyEpochInboxV2();
        var a=new HydrologyTransferLedgerV2.Key(2,at(4),at(5));
        var b=new HydrologyTransferLedgerV2.Key(3,at(6),at(5));
        assertTrue(inbox.receive(a,4));
        assertTrue(inbox.receive(b,6));
        assertFalse(inbox.receive(a,4));
        var first=HydrologyReceiptBudgetV2.summarize(inbox.snapshot());
        assertEquals(10,first.pendingVolume(),1e-12);
        assertEquals(0,first.deliveredVolume(),1e-12);
        assertEquals(Map.of(at(5),10.0),first.pendingByDestination());
        assertEquals(Map.of(at(5),4.0),inbox.claimAvailable(2,Set.of(at(5))));
        var restored=HydrologyEpochInboxV2.restore(
            HydrologyInboxCodecV2.decode(HydrologyInboxCodecV2.encode(inbox.snapshot())));
        var middle=HydrologyReceiptBudgetV2.summarize(restored.snapshot());
        assertEquals(6,middle.pendingVolume(),1e-12);
        assertEquals(4,middle.deliveredVolume(),1e-12);
        assertEquals(10,middle.totalVolume(),1e-12);
        assertEquals(Map.of(),restored.claimAvailable(3,Set.of()));
        assertEquals(Map.of(at(5),6.0),restored.claimAvailable(4,Set.of(at(5))));
        var finalBudget=HydrologyReceiptBudgetV2.summarize(restored.snapshot());
        assertEquals(0,finalBudget.pendingVolume(),1e-12);
        assertEquals(10,finalBudget.deliveredVolume(),1e-12);
        assertEquals(10,finalBudget.totalVolume(),1e-12);
        assertTrue(finalBudget.pendingByDestination().isEmpty());
    }

    @Test void rejectsReceiptTotalsBeyondRepresentableWater() {
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(2,at(4),at(5)),
            Double.MAX_VALUE);
        inbox.receive(new HydrologyTransferLedgerV2.Key(2,at(6),at(5)),
            Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyReceiptBudgetV2.summarize(inbox.snapshot()));
    }
}
