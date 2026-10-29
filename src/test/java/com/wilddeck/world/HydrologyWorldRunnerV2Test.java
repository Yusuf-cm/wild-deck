package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyWorldRunnerV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    private static List<List<HydrologyFlowGraphV2.Node>> graph(){
        return List.of(List.of(
            new HydrologyFlowGraphV2.Node(at(5),1,at(6),false),
            new HydrologyFlowGraphV2.Node(at(6),2,null,true)));
    }
    @Test void advancesEpochsAndRestoresCommittedState() throws IOException {
        var path=directory.resolve("world.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"initial");
        runner.receive(new HydrologyTransferLedgerV2.Key(1,at(4),at(5)),5);
        var result=runner.advance("world-1",graph());
        assertEquals(8,result.terminalOutflow().get(at(6)),1e-9);
        assertEquals(1,runner.epoch());
        var restored=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,restored.epoch());
        assertEquals("world-1",restored.worldState());
        assertThrows(IllegalArgumentException.class,()->
            restored.receive(new HydrologyTransferLedgerV2.Key(1,at(4),at(5)),5));
        var next=restored.advance("world-2",graph());
        assertEquals(3,next.terminalOutflow().get(at(6)),1e-9);
        assertEquals(2,HydrologyWorldRunnerV2.open(path,0,"ignored").epoch());
    }
    @Test void loadsFocusedRegionAndAdvancesWithProvider() throws IOException {
        var runner=HydrologyWorldRunnerV2.open(directory.resolve("focused.chk"),0,"initial");
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var result=runner.advanceRegions("next",
            List.of(new HydrologyRegionSchedulerV2.Region(10,20)),0,1,region->{
                visited.add(region);
                return graph().get(0);
            });
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(10,20)),visited);
        assertEquals(3,result.terminalOutflow().get(at(6)),1e-9);
        assertEquals(1,runner.epoch());
    }

    @Test void incomingWaterSurvivesRestartBeforeAnyAdvance() throws IOException {
        Path path=directory.resolve("durable-receipts.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"initial");
        var key=new HydrologyTransferLedgerV2.Key(1,at(4),at(5));
        assertTrue(runner.receive(key,5));
        var restored=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertFalse(restored.receive(key,5));
        assertThrows(IllegalArgumentException.class,()->restored.receive(key,6));
        assertEquals(8,restored.advance("world-1",graph())
            .terminalOutflow().get(at(6)),1e-9);
        assertEquals(1,HydrologyWorldRunnerV2.open(path,0,"ignored").epoch());
    }

    @Test void failedReceiptWriteDoesNotAcknowledgeWater() throws IOException {
        Path path=directory.resolve("missing-after-open.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"initial");
        Files.delete(path);
        assertThrows(IOException.class,()->runner.receive(
            new HydrologyTransferLedgerV2.Key(1,at(4),at(5)),5));
        assertTrue(runner.inboxSnapshot().receipts().isEmpty());
        assertEquals(0,runner.epoch());
    }

    @Test void knownFrontierConfluenceSurvivesRestartAndIsConsumedOnce() throws IOException {
        Path path=directory.resolve("frontier.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"initial");
        var upstream=List.of(
            new HydrologyFlowGraphV2.Node(at(4),3,at(5),false),
            new HydrologyFlowGraphV2.Node(at(4,4),2,at(5),false));
        var routed=runner.advanceAndQueueKnownFrontier("upstream",List.of(upstream));
        assertEquals(2,routed.queuedTransfers());
        assertEquals(5,routed.committed().flow().pendingOutflow().get(at(5)),1e-9);
        var reloaded=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(2,reloaded.inboxSnapshot().receipts().size());
        var receiver=List.of(
            new HydrologyFlowGraphV2.Node(at(5),1,at(6),false),
            new HydrologyFlowGraphV2.Node(at(6),0,null,true));
        assertEquals(6,reloaded.advanceAndQueueKnownFrontier("downstream",List.of(receiver))
            .committed().flow().terminalOutflow().get(at(6)),1e-9);
        var again=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(2,again.epoch());
        assertEquals(1,again.advance("another-rainfall",List.of(receiver))
            .terminalOutflow().get(at(6)),1e-9);
        assertThrows(IllegalArgumentException.class,()->again.receive(
            new HydrologyTransferLedgerV2.Key(2,at(4),at(5)),3));
    }

    @Test void unknownFrontierAbortsWithoutLosingWater() throws IOException {
        Path path=directory.resolve("unknown.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"initial");
        var unknown=List.of(new HydrologyFlowGraphV2.Node(at(4),7,null,false));
        assertThrows(IllegalStateException.class,()->
            runner.advanceAndQueueKnownFrontier("should-not-save",List.of(unknown)));
        assertEquals(0,runner.epoch());
        assertEquals("initial",HydrologyWorldRunnerV2.open(path,0,"ignored").worldState());
    }

    @Test void failedAdvancePreservesEpochAndInbox() throws IOException {
        var runner=HydrologyWorldRunnerV2.open(directory.resolve("world.chk"),0,"initial");
        runner.receive(new HydrologyTransferLedgerV2.Key(1,at(4),at(5)),5);
        var cycle=List.of(List.of(
            new HydrologyFlowGraphV2.Node(at(5),1,at(6),false),
            new HydrologyFlowGraphV2.Node(at(6),2,at(5),false)));
        assertThrows(IllegalArgumentException.class,()->runner.advance("bad",cycle));
        assertEquals(0,runner.epoch());
        assertEquals(5,runner.inboxSnapshot().receipts().values().iterator().next());
        assertEquals(8,runner.advance("good",graph()).terminalOutflow().get(at(6)),1e-9);
    }
}
