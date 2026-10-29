package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyEpochInboxV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    @Test void claimIsExactlyOnceInMemoryAndAcrossSnapshotRestore(){
        var inbox=new HydrologyEpochInboxV2();
        var key=new HydrologyTransferLedgerV2.Key(7,at(4),at(5));
        assertTrue(inbox.receive(key,6));
        assertFalse(inbox.receive(key,6));
        assertEquals(Map.of(at(5),6.0),inbox.claim(7));
        assertThrows(IllegalStateException.class,()->inbox.claim(7));
        assertThrows(IllegalStateException.class,()->inbox.receive(key,6));
        var restored=HydrologyEpochInboxV2.restore(inbox.snapshot());
        assertThrows(IllegalStateException.class,()->restored.claim(7));
        assertEquals(Map.of(),restored.claim(8));
    }
    @Test void rejectsConflictingReplayAndKeepsEpochsSeparate(){
        var inbox=new HydrologyEpochInboxV2();
        var first=new HydrologyTransferLedgerV2.Key(1,at(4),at(5));
        var second=new HydrologyTransferLedgerV2.Key(2,at(4),at(5));
        inbox.receive(first,3);
        inbox.receive(second,8);
        assertThrows(IllegalArgumentException.class,()->inbox.receive(first,5));
        assertEquals(3,inbox.claim(1).get(at(5)));
        assertEquals(8,inbox.claim(2).get(at(5)));
    }

    @Test void overflowDoesNotMarkEpochConsumed(){
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(4,at(4),at(5)),Double.MAX_VALUE);
        inbox.receive(new HydrologyTransferLedgerV2.Key(4,at(6),at(5)),Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->inbox.claim(4));
        assertFalse(inbox.snapshot().consumedEpochs().contains(4L));
    }
}
