package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyDurableEpochV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    private static List<List<HydrologyFlowGraphV2.Node>> graph(){
        return List.of(List.of(
            new HydrologyFlowGraphV2.Node(at(5),2,at(6),false),
            new HydrologyFlowGraphV2.Node(at(6),1,null,true)));
    }

    @Test void committedEpochSurvivesReloadAndDoesNotReplayWater() throws IOException {
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(7,at(4),at(5)),4);
        var file=directory.resolve("checkpoint.bin");
        var committed=HydrologyDurableEpochV2.commit(file,7,"world-after-epoch-7",inbox,graph());
        assertEquals(7,committed.flow().terminalOutflow().get(at(6)),1e-9);
        assertEquals("world-after-epoch-7",HydrologyCheckpointStoreV2.load(file).worldState());
        var recovered=HydrologyCheckpointV2.restoreInbox(HydrologyCheckpointStoreV2.load(file));
        assertThrows(IllegalStateException.class,()->recovered.claim(7));
        assertEquals(Map.of(at(5),4.0),inbox.claim(7)); // original was not mutated
    }

    @Test void failedSolveDoesNotConsumeInboxOrWriteCheckpoint(){
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(2,at(4),at(5)),4);
        var cycle=List.of(List.of(
            new HydrologyFlowGraphV2.Node(at(5),1,at(6),false),
            new HydrologyFlowGraphV2.Node(at(6),1,at(5),false)));
        var file=directory.resolve("checkpoint.bin");
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDurableEpochV2.commit(file,2,"bad",inbox,cycle));
        assertFalse(Files.exists(file));
        assertEquals(4,inbox.claim(2).get(at(5)));
    }

    @Test void failedCheckpointWriteDoesNotConsumeInbox(){
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(3,at(4),at(5)),4);
        assertThrows(IOException.class,()->
            HydrologyDurableEpochV2.commit(directory.resolve("missing").resolve("file"),3,
                "world",inbox,graph()));
        assertEquals(4,inbox.claim(3).get(at(5)));
    }
}
