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

    /**
     * Persist each future-epoch receipt before acknowledging delivery.
     * A crash before the next advance therefore cannot silently lose water.
     */
    public synchronized boolean receive(HydrologyTransferLedgerV2.Key key,double flow)
            throws IOException {
        Objects.requireNonNull(key);
        if(key.epoch()<=last.epoch())
            throw new IllegalArgumentException("Transfer targets committed epoch");
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        if(!staged.receive(key,flow))return false;
        var checkpoint=HydrologyCheckpointV2.capture(last.epoch(),last.worldState(),staged);
        HydrologyCheckpointStoreV2.saveIncoming(checkpointPath,checkpoint);
        inbox=staged;
        last=checkpoint;
        return true;
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

    /**
     * Advance and durably queue known cross-region boundary edges for the
     * next epoch. Unknown downstream routes abort instead of losing water.
     */
    public synchronized HydrologyDurableEpochV2.Routed advanceAndQueueKnownFrontier(
            String nextWorldState,
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> loaded)
            throws IOException {
        Objects.requireNonNull(nextWorldState);
        long next=Math.addExact(last.epoch(),1);
        var routed=HydrologyDurableEpochV2.commitAndQueueKnownFrontier(
            checkpointPath,next,nextWorldState,inbox,loaded);
        last=routed.committed().checkpoint();
        inbox=routed.committed().inbox();
        return routed;
    }

    /** Select bounded regions deterministically, then advance one epoch. */
    public synchronized HydrologyFlowGraphV2.Result advanceRegions(
            String nextWorldState,
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            int radius,int maximumRegions,
            HydrologyRegionSchedulerV2.Provider provider) throws IOException {
        var loaded=HydrologyRegionSchedulerV2.load(focus,radius,maximumRegions,provider);
        return advance(nextWorldState,loaded);
    }

    /**
     * Load explicit focus regions together with parked-water destinations,
     * then advance and durably queue known outgoing edges. This operation
     * fails closed on unknown routes and never commits partial region loads.
     * The provider must supply authoritative graph nodes for each region;
     * this method does not invent routes through unsampled terrain.
     */
    public synchronized HydrologyDurableEpochV2.Routed advancePendingRegionsAndQueue(
            String nextWorldState,
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            int radius,int maximumRegions,
            HydrologyRegionSchedulerV2.Provider provider) throws IOException {
        var loaded=HydrologyRegionSchedulerV2.loadWithPending(
            focus,inbox.snapshot(),radius,maximumRegions,provider);
        return advanceAndQueueKnownFrontier(nextWorldState,loaded);
    }

    /**
     * Generate a connected terrain halo around a bounded core, use only the
     * core's rainfall vertices, and durably queue known outward river edges.
     * Unknown routes at the planet/halo boundary fail closed. This does not
     * resolve the entire planetary watershed or mutate persistent terrain.
     */
    public synchronized HydrologyDurableEpochV2.Routed advanceTerrainCoreAndQueue(
            String nextWorldState,PlanetSurfaceV2 terrain,PlanetClimateV2 climate,
            int level,long firstColumn,long firstRow,int columns,int rows,
            int divisions,int haloRegions,double riverThreshold) throws IOException {
        var core=HydrologyTerrainWindowV2.generateCoreWithHalo(
            terrain,climate,level,firstColumn,firstRow,columns,rows,
            divisions,haloRegions,riverThreshold);
        return advanceAndQueueKnownFrontier(nextWorldState,List.of(core.coreNodes()));
    }

    /** Return deterministic region hints for receipts still awaiting delivery. */
    public synchronized List<HydrologyRegionSchedulerV2.Region> pendingWaterRegions(){
        return HydrologyRegionSchedulerV2.pendingRegions(inbox.snapshot());
    }

    public synchronized HydrologyEpochInboxV2.Snapshot inboxSnapshot(){
        return inbox.snapshot();
    }
}
