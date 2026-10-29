package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Single-writer local simulation runner for already prepared global flow nodes.
 * Advances a monotonic epoch and atomically checkpoints world + water inbox.
 * World-state evolution and loading of terrain/regions remain caller-supplied.
 */
public final class HydrologyWorldRunnerV2 {
    private final Path checkpointPath;
    private HydrologyCheckpointV2.Checkpoint last;
    private HydrologyEpochInboxV2 inbox;

    private HydrologyWorldRunnerV2(Path checkpointPath,
                                   HydrologyCheckpointV2.Checkpoint last,
                                   HydrologyEpochInboxV2 inbox) {
        this.checkpointPath=checkpointPath;
        this.last=last;
        this.inbox=inbox;
    }

    public static HydrologyWorldRunnerV2 open(Path checkpointPath,
                                              long initialEpoch,
                                              String initialWorldState) throws IOException {
        Objects.requireNonNull(checkpointPath);
        Objects.requireNonNull(initialWorldState);
        if(initialEpoch<0)throw new IllegalArgumentException("Invalid epoch");
        if(Files.exists(checkpointPath)){
            var checkpoint=HydrologyCheckpointStoreV2.load(checkpointPath);
            return new HydrologyWorldRunnerV2(checkpointPath,checkpoint,
                HydrologyCheckpointV2.restoreInbox(checkpoint));
        }
        var inbox=new HydrologyEpochInboxV2();
        var checkpoint=HydrologyCheckpointV2.capture(initialEpoch,initialWorldState,inbox);
        HydrologyCheckpointStoreV2.save(checkpointPath,checkpoint);
        return new HydrologyWorldRunnerV2(checkpointPath,checkpoint,inbox);
    }

    public synchronized long epoch(){return last.epoch();}
    public synchronized String worldState(){return last.worldState();}

    /** Deliver only to future epochs; current and earlier epochs are closed. */
    public synchronized boolean receive(HydrologyTransferLedgerV2.Key key,double flow) {
        Objects.requireNonNull(key);
        if(key.epoch()<=last.epoch())
            throw new IllegalArgumentException("Transfer targets committed epoch");
        return inbox.receive(key,flow);
    }

    /**
     * Advance exactly one epoch using the caller's new serialized world state
     * and currently loaded region flow graphs. Failure leaves runner unchanged.
     */
    public synchronized HydrologyFlowGraphV2.Result advance(
            String nextWorldState,
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> loaded)
            throws IOException {
        Objects.requireNonNull(nextWorldState);
        long next=Math.addExact(last.epoch(),1);
        var committed=HydrologyDurableEpochV2.commit(
            checkpointPath,next,nextWorldState,inbox,loaded);
        last=committed.checkpoint();
        inbox=committed.inbox();
        return committed.flow();
    }

    public synchronized HydrologyEpochInboxV2.Snapshot inboxSnapshot(){
        return inbox.snapshot();
    }
}
