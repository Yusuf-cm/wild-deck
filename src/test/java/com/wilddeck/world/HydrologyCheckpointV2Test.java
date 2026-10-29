package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyCheckpointV2Test {
    @Test void capturesWorldAndConsumedInboxInOneImmutableValue(){
        var inbox=new HydrologyEpochInboxV2();
        var from=new GlobalHydrologyAddressV2.Sample(2,4,4,3);
        var to=new GlobalHydrologyAddressV2.Sample(2,4,5,3);
        inbox.receive(new HydrologyTransferLedgerV2.Key(8,from,to),12);
        inbox.claim(8);
        var checkpoint=HydrologyCheckpointV2.capture(8,"world-state-v1",inbox);
        assertEquals("world-state-v1",checkpoint.worldState());
        var restored=HydrologyCheckpointV2.restoreInbox(checkpoint);
        assertThrows(IllegalStateException.class,()->restored.claim(8));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyCheckpointV2.Checkpoint(8,"world","corrupted"));
    }
}
