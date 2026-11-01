package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalTransferLedgerV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }

    @Test void seamAndPolarTransferKeysSurviveInboxSerialization() {
        var seam=new HydrologyTransferLedgerV2.Key(3,at(31,5),at(0,5));
        var pole=new HydrologyTransferLedgerV2.Key(3,at(27,1),at(0,0));
        var inbox=new HydrologyEpochInboxV2();
        assertTrue(inbox.receive(seam,5));
        assertTrue(inbox.receive(pole,7));
        assertFalse(inbox.receive(seam,5));
        assertThrows(IllegalArgumentException.class,()->inbox.receive(seam,6));
        var restored=HydrologyInboxCodecV2.decode(
            HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertEquals(inbox.snapshot(),restored);
        assertEquals(Map.of(at(0,5),5.0,at(0,0),7.0),
            HydrologyEpochInboxV2.restore(restored).claim(3));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTransferLedgerV2.Key(3,at(12,5),at(0,5)));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTransferLedgerV2.Key(3,at(27,2),at(0,0)));
    }
}
