package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance: incoming water survives multiple unloaded epochs without
 * being counted twice when the receiving region eventually becomes loaded.
 */
class HydrologyDeferredReceiptsV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    private static List<List<HydrologyFlowGraphV2.Node>> terminal(long x,double rainfall){
        return List.of(List.of(new HydrologyFlowGraphV2.Node(at(x),rainfall,null,true)));
    }

    @Test void unloadedWaterWaitsAcrossMultipleCheckpointsThenArrivesExactlyOnce()
            throws IOException {
        Path path=directory.resolve("parked.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"seed");
        var loadedKey=new HydrologyTransferLedgerV2.Key(1,at(4),at(5));
        var parkedKey=new HydrologyTransferLedgerV2.Key(1,at(6),at(7));
        assertTrue(runner.receive(loadedKey,4));
        assertTrue(runner.receive(parkedKey,6));
        assertEquals(5,runner.advanceAndQueueKnownFrontier("first",terminal(5,1))
            .committed().flow().terminalOutflow().get(at(5)),1e-9);
        assertEquals(Set.of(loadedKey),runner.inboxSnapshot().delivered());

        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(0,runner.advanceAndQueueKnownFrontier("second",terminal(5,0))
            .committed().flow().terminalOutflow().get(at(5)),1e-9);
        assertEquals(Set.of(loadedKey),runner.inboxSnapshot().delivered());

        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(8,runner.advanceAndQueueKnownFrontier("third",terminal(7,2))
            .committed().flow().terminalOutflow().get(at(7)),1e-9);
        assertEquals(Set.of(loadedKey,parkedKey),runner.inboxSnapshot().delivered());
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(2,runner.advanceAndQueueKnownFrontier("fourth",terminal(7,2))
            .committed().flow().terminalOutflow().get(at(7)),1e-9);
        assertThrows(IllegalArgumentException.class,()->HydrologyWorldRunnerV2.open(path,0,"ignored")
            .receive(parkedKey,6));
        assertEquals(4,HydrologyWorldRunnerV2.open(path,0,"ignored").epoch());
    }

    @Test void legacyV2ConsumedEpochMigratesToDeliveredMarkers(){
        String legacy="wilddeck-hydrology-inbox-v2\nC,1\nR,1,2,4,4,3,5,3,4.0\n";
        var decoded=HydrologyInboxCodecV2.decode(legacy);
        assertEquals(1,decoded.delivered().size());
        String upgraded=HydrologyInboxCodecV2.encode(decoded);
        assertTrue(upgraded.startsWith(HydrologyInboxCodecV2.HEADER));
        assertEquals(decoded,HydrologyInboxCodecV2.decode(upgraded));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyInboxCodecV2.decode(HydrologyInboxCodecV2.HEADER+
                "\nC,1\nD,1,2,4,4,3,5,3\n"));
    }

    @Test void aggregationOverflowDoesNotMarkReceiptsDelivered(){
        var inbox=new HydrologyEpochInboxV2();
        var a=new HydrologyTransferLedgerV2.Key(1,at(4),at(5));
        var b=new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2,4,4,4),at(5));
        inbox.receive(a,1e308);
        inbox.receive(b,1e308);
        assertThrows(IllegalArgumentException.class,()->
            inbox.claimAvailable(1,Set.of(at(5))));
        assertTrue(inbox.snapshot().delivered().isEmpty());
        assertTrue(inbox.snapshot().consumedEpochs().isEmpty());
        assertEquals(Map.of(),inbox.claimAvailable(1,Set.of(at(7))));
        assertTrue(inbox.snapshot().delivered().isEmpty());
    }
}
