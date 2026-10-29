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

    @Test void ordinaryAdvanceAlsoDeliversPreviouslyParkedReceipts() throws IOException {
        Path path=directory.resolve("ordinary.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"seed");
        var key=new HydrologyTransferLedgerV2.Key(1,at(6),at(7));
        runner.receive(key,9);
        runner.advance("first",terminal(5,0));
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(11,runner.advance("second",terminal(7,2))
            .terminalOutflow().get(at(7)),1e-9);
        assertEquals(Set.of(key),runner.inboxSnapshot().delivered());
        assertEquals(2,runner.advance("third",terminal(7,2))
            .terminalOutflow().get(at(7)),1e-9);
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

    @Test void delayedConfluencePreservesEachSourceAndDoesNotReplay() throws IOException {
        Path path=directory.resolve("confluence.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"seed");
        var a=new HydrologyTransferLedgerV2.Key(1,at(4),at(5));
        var b=new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2,4,4,4),at(5));
        runner.receive(a,4);
        runner.receive(b,7);
        runner.advanceAndQueueKnownFrontier("unloaded-1",terminal(7,0));
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        runner.advanceAndQueueKnownFrontier("unloaded-2",terminal(7,0));
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(12,runner.advanceAndQueueKnownFrontier("loaded",terminal(5,1))
            .committed().flow().terminalOutflow().get(at(5)),1e-9);
        assertEquals(Set.of(a,b),runner.inboxSnapshot().delivered());
        assertEquals(1,runner.advanceAndQueueKnownFrontier("next",terminal(5,1))
            .committed().flow().terminalOutflow().get(at(5)),1e-9);
    }

    @Test void sameEdgeDifferentEpochsParkWithoutReceiptCollision() throws IOException {
        Path path=directory.resolve("epoch-identity.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"seed");
        var first=new HydrologyTransferLedgerV2.Key(1,at(4),at(5));
        var second=new HydrologyTransferLedgerV2.Key(2,at(4),at(5));
        runner.receive(first,2);
        runner.advanceAndQueueKnownFrontier("one",terminal(7,0));
        runner.receive(second,3);
        runner.advanceAndQueueKnownFrontier("two",terminal(7,0));
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(6,runner.advanceAndQueueKnownFrontier("three",terminal(5,1))
            .committed().flow().terminalOutflow().get(at(5)),1e-9);
        assertEquals(Set.of(first,second),runner.inboxSnapshot().delivered());
        assertEquals(1,runner.advanceAndQueueKnownFrontier("four",terminal(5,1))
            .committed().flow().terminalOutflow().get(at(5)),1e-9);
    }

    @Test void receiptOnlyCheckpointCannotForgeDelivery() throws IOException {
        Path path=directory.resolve("receipt-only.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"seed");
        var key=new HydrologyTransferLedgerV2.Key(1,at(4),at(5));
        runner.receive(key,3);
        var before=HydrologyCheckpointStoreV2.load(path);
        var forged=HydrologyCheckpointV2.restoreInbox(before);
        forged.claimAvailable(1,Set.of(at(5)));
        assertThrows(IOException.class,()->HydrologyCheckpointStoreV2.saveIncoming(path,
            HydrologyCheckpointV2.capture(0,"seed",forged)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void pendingWaterSuggestsStableOwningRegionsUntilDelivery() throws IOException {
        Path path=directory.resolve("region-hints.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"seed");
        runner.receive(new HydrologyTransferLedgerV2.Key(1,at(4),at(5)),2);
        runner.receive(new HydrologyTransferLedgerV2.Key(1,at(7),at(8)),3);
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(1,0)),
            runner.pendingWaterRegions()); // x=8 belongs to western tile at boundary
        runner.advanceAndQueueKnownFrontier("loaded-5",terminal(5,0));
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(1,0)),
            runner.pendingWaterRegions());
        runner.advanceAndQueueKnownFrontier("loaded-8",terminal(8,0));
        assertTrue(runner.pendingWaterRegions().isEmpty());
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
