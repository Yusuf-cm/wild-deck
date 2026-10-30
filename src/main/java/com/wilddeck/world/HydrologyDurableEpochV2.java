package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Single-writer checkpointed water epoch. Stage inbox consumption on a copy,
 * solve the loaded flow graph, then commit world+inbox in one local checkpoint.
 * If solve or checkpoint write fails, the caller's inbox remains unchanged.
 *
 * The returned inbox must replace the old one after successful save. The
 * caller is responsible for loading the last committed world and inbox on
 * restart, and for supplying the correct epoch and serialized world state.
 */
public final class HydrologyDurableEpochV2 {
    private HydrologyDurableEpochV2(){}

    public record Committed(HydrologyFlowGraphV2.Result flow,
                            HydrologyEpochInboxV2 inbox,
                            HydrologyCheckpointV2.Checkpoint checkpoint) {
        public Committed {
            Objects.requireNonNull(flow);
            Objects.requireNonNull(inbox);
            Objects.requireNonNull(checkpoint);
        }
    }

    /** A committed epoch with its known unloaded-edge receipts queued durably. */
    public record Routed(Committed committed,int queuedTransfers) {
        public Routed {
            Objects.requireNonNull(committed);
            if(queuedTransfers<0)throw new IllegalArgumentException("Invalid transfer count");
        }
    }

    /**
     * Advance and queue each known outgoing edge for the following epoch.
     * Unknown downstreams fail closed: silently discarding parked water would
     * violate conservation. All receipts and consumption are committed with
     * the new world state in a single atomic checkpoint replacement.
     */
    public static Routed commitAndQueueKnownFrontier(
            Path destination,long epoch,String serializedWorld,
            HydrologyEpochInboxV2 inbox,
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> mosaics)
            throws IOException {
        return commitAndQueueKnownFrontier(destination,epoch,serializedWorld,
            inbox,mosaics,null);
    }

    /** Expected checkpoint is required for runner-backed stale-writer protection. */
    public static Routed commitAndQueueKnownFrontier(
            Path destination,long epoch,String serializedWorld,
            HydrologyEpochInboxV2 inbox,
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> mosaics,
            HydrologyCheckpointV2.Checkpoint expected)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(serializedWorld);
        Objects.requireNonNull(inbox);
        Objects.requireNonNull(mosaics);
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        // Only consume water whose receiving vertex is actually loaded.
        // Preserve receipts addressed to unloaded regions with their original
        // edge/epoch identity until a later solve includes that vertex.
        var loaded=HydrologyMosaicJoinV2.merge(mosaics);
        var addresses=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:loaded)addresses.add(node.address());
        var incoming=staged.claimAvailable(epoch,addresses);
        var nodes=HydrologyEpochSolverV2.prepare(List.of(loaded),incoming);
        var result=HydrologyFlowGraphV2.solve(nodes);
        HydrologyConservationV2.verify(nodes,result);
        var dispatch=HydrologyFrontierDispatchV2.export(Math.addExact(epoch,1),nodes,result);
        if(!dispatch.unresolved().isEmpty())
            throw new IllegalStateException("Unresolved routes require durable parking before advance");
        int queued=dispatch.deliver(staged);
        var checkpoint=HydrologyCheckpointV2.capture(epoch,serializedWorld,staged);
        if(expected==null)HydrologyCheckpointStoreV2.save(destination,checkpoint);
        else HydrologyCheckpointStoreV2.saveNext(destination,expected,checkpoint);
        return new Routed(new Committed(result,staged,checkpoint),queued);
    }

    public static Committed commit(Path destination,long epoch,String serializedWorld,
            HydrologyEpochInboxV2 inbox,
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> mosaics)
            throws IOException {
        return commit(destination,epoch,serializedWorld,inbox,mosaics,null);
    }

    /** Expected checkpoint is required for runner-backed stale-writer protection. */
    public static Committed commit(Path destination,long epoch,String serializedWorld,
            HydrologyEpochInboxV2 inbox,
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> mosaics,
            HydrologyCheckpointV2.Checkpoint expected)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(serializedWorld);
        Objects.requireNonNull(inbox);
        Objects.requireNonNull(mosaics);
        // Never mutate the original inbox before durable checkpoint success.
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        var loaded=HydrologyMosaicJoinV2.merge(mosaics);
        var addresses=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:loaded)addresses.add(node.address());
        var incoming=staged.claimAvailable(epoch,addresses);
        var prepared=HydrologyEpochSolverV2.prepare(List.of(loaded),incoming);
        var result=HydrologyFlowGraphV2.solve(prepared);
        HydrologyConservationV2.verify(prepared,result);
        var checkpoint=HydrologyCheckpointV2.capture(epoch,serializedWorld,staged);
        if(expected==null)HydrologyCheckpointStoreV2.save(destination,checkpoint);
        else HydrologyCheckpointStoreV2.saveNext(destination,expected,checkpoint);
        return new Committed(result,staged,checkpoint);
    }
}
