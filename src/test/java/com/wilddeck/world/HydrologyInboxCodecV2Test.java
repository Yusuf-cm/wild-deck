package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyInboxCodecV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    @Test void deterministicRoundtripRestoresConsumedEpoch(){
        var inbox=new HydrologyEpochInboxV2();
        var first=new HydrologyTransferLedgerV2.Key(2,at(4),at(5));
        var second=new HydrologyTransferLedgerV2.Key(1,at(5),at(6));
        inbox.receive(first,7);
        inbox.receive(second,3);
        inbox.claim(2);
        var encoded=HydrologyInboxCodecV2.encode(inbox.snapshot());
        var restored=HydrologyEpochInboxV2.restore(HydrologyInboxCodecV2.decode(encoded));
        assertEquals(encoded,HydrologyInboxCodecV2.encode(restored.snapshot()));
        assertThrows(IllegalStateException.class,()->restored.claim(2));
        assertEquals(3,restored.claim(1).get(at(6)));
    }
    @Test void rejectsCorruptionAndDuplicateReceipts(){
        assertThrows(IllegalArgumentException.class,()->
            HydrologyInboxCodecV2.decode("wrong\n"));
        String duplicate=HydrologyInboxCodecV2.HEADER+"\nC,2\nC,2\n";
        assertThrows(IllegalArgumentException.class,()->
            HydrologyInboxCodecV2.decode(duplicate));
        String invalid=HydrologyInboxCodecV2.HEADER+"\nR,1,2,4,4,3,5,3,-1\n";
        assertThrows(IllegalArgumentException.class,()->
            HydrologyInboxCodecV2.decode(invalid));
    }
}
