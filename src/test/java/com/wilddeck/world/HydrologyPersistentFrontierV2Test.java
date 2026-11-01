package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One region unload/reload cycle: pending water is checkpointed, consumed
 * exactly once in the next epoch and checkpointed with the new world state.
 */
class HydrologyPersistentFrontierV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y){
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }

    @Test void restartPreservesPendingWaterAndCommittedConsumption() throws IOException {
        Path file=directory.resolve("world.hydro");
        var inlet=at(5,3);
        var upstream=List.of(
            new HydrologyFlowGraphV2.Node(at(4,2),3,inlet,false),
            new HydrologyFlowGraphV2.Node(at(4,3),2,inlet,false));
        var export=HydrologyFrontierDispatchV2.export(3,upstream,
            HydrologyFlowGraphV2.solve(upstream));
        var inbox=new HydrologyEpochInboxV2();
        assertEquals(2,export.deliver(inbox));

        // Save the world BEFORE receiving tile is loaded.
        HydrologyCheckpointStoreV2.save(file,
            HydrologyCheckpointV2.capture(3,"receiver-unloaded",inbox));
        var pending=HydrologyCheckpointStoreV2.load(file);
        assertEquals("receiver-unloaded",pending.worldState());
        var restored=HydrologyCheckpointV2.restoreInbox(pending);
        assertEquals(0,export.deliver(restored)); // persisted duplicate receipts

        var receiver=List.of(
            new HydrologyFlowGraphV2.Node(inlet,1,at(6,3),false),
            new HydrologyFlowGraphV2.Node(at(6,3),0,null,true));
        var result=HydrologyEpochSolverV2.solve(List.of(receiver),restored.claim(3));
        assertEquals(6,result.terminalOutflow().get(at(6,3)),1e-9);
        assertTrue(result.pendingOutflow().isEmpty());

        // World advancement and consumption markers are one committed file.
        HydrologyCheckpointStoreV2.save(file,
            HydrologyCheckpointV2.capture(4,"receiver-solved:6",restored));
        var afterRestart=HydrologyCheckpointStoreV2.load(file);
        assertEquals("receiver-solved:6",afterRestart.worldState());
        var afterInbox=HydrologyCheckpointV2.restoreInbox(afterRestart);
        assertThrows(IllegalStateException.class,()->afterInbox.claim(3));
        assertThrows(IllegalStateException.class,()->export.deliver(afterInbox));
        assertEquals(4,afterRestart.epoch());
    }
}
