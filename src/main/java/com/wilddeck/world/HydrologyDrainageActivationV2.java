package com.wilddeck.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.Base64;

/**
 * Opt-in single-writer, lake-free drainage transition checkpoint.
 *
 * Only a receipt-only routing epoch is supported: no new rainfall, snow/soil
 * reservoirs, active lakes, erosion or simultaneous external producers. The
 * complete verified new atlas must be supplied again after restart. The
 * checkpoint binds its ID but does NOT serialize the atlas or coordinate
 * remote writers. A caller must quiesce producers before this transition.
 */
public final class HydrologyDrainageActivationV2 {
    private HydrologyDrainageActivationV2() {}
    private static final String MAGIC="WD-DRAINAGE-ACTIVATION-1";

    public record BoundState(String topology,String lakeState) {
        public BoundState {
            Objects.requireNonNull(topology);
            Objects.requireNonNull(lakeState);
            if(!topology.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid drainage topology ID");
            HydrologyLakeStateV2.decode(lakeState);
        }
    }

    public record Committed(HydrologyFlowGraphV2.Result flow,
                            HydrologyConservationV2.Budget budget,
                            HydrologyEpochInboxV2 inbox,
                            HydrologyCheckpointV2.Checkpoint checkpoint,
                            HydrologyDrainageLandingPlanV2.Plan landings) {
        public Committed {
            Objects.requireNonNull(flow);
            Objects.requireNonNull(budget);
            Objects.requireNonNull(inbox);
            Objects.requireNonNull(checkpoint);
            Objects.requireNonNull(landings);
        }
    }

    /** Initialize an explicitly bound checkpoint with an existing lake codec payload. */
    public static String bind(HydrologySphericalRouteAtlasV2 atlas,String lakeState) {
        return encode(new BoundState(HydrologyDrainageSnapshotIdV2.of(atlas),lakeState));
    }

    public static String encode(BoundState state) {
        Objects.requireNonNull(state);
        return MAGIC+"\n"+state.topology()+"\n"+
            Base64.getEncoder().encodeToString(
                state.lakeState().getBytes(StandardCharsets.UTF_8))+"\n";
    }

    public static BoundState decode(String payload) {
        Objects.requireNonNull(payload);
        var parts=payload.split("\\n",-1);
        if(parts.length!=4||!MAGIC.equals(parts[0])||!parts[3].isEmpty())
            throw new IllegalArgumentException("Unsupported drainage checkpoint");
        try {
            var bytes=Base64.getDecoder().decode(parts[2]);
            var lake=StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString();
            var result=new BoundState(parts[1],lake);
            if(!encode(result).equals(payload))
                throw new IllegalArgumentException("Noncanonical drainage checkpoint");
            return result;
        } catch(CharacterCodingException|IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed drainage checkpoint",ex);
        }
    }

    /**
     * Consume all old-edge pending receipts at their RECORDED destinations,
     * route them once through the rebuilt closed graph, and checkpoint the
     * new topology ID with receipt delivery in one local atomic replacement.
     * Rejects future-epoch receipts, unbound states and nonempty lake storage.
     * Does not perform a general live-world or lake-storage migration.
     */
    public static Committed commit(Path checkpointPath,long nextEpoch,
            HydrologyCheckpointV2.Checkpoint current,
            HydrologyEpochInboxV2 inbox,
            HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit) throws IOException {
        Objects.requireNonNull(checkpointPath);
        Objects.requireNonNull(current);
        Objects.requireNonNull(inbox);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(audit);
        if(nextEpoch!=Math.addExact(current.epoch(),1))
            throw new IllegalArgumentException("Drainage activation must advance one epoch");
        if(!HydrologyCheckpointV2.restoreInbox(current).snapshot()
                .equals(inbox.snapshot()))
            throw new IllegalArgumentException("Inbox differs from committed checkpoint");
        var bound=decode(current.worldState());
        if(!bound.topology().equals(HydrologyDrainageSnapshotIdV2.of(previous)) ||
           !bound.topology().equals(audit.previousTopology()))
            throw new IllegalArgumentException("Stale drainage checkpoint topology");
        if(!HydrologyLakeStateV2.decode(bound.lakeState()).volumes().isEmpty())
            throw new IllegalArgumentException("Live lake storage requires reconciliation");

        var landing=HydrologyDrainageLandingPlanV2.inspect(
            previous,audit,inbox.snapshot());
        for(var item:landing.landings())
            if(item.receipt().epoch()>nextEpoch)
                throw new IllegalArgumentException("Future old-topology water still in flight");
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        var rebuilt=audit.rebuilt();
        var incoming=staged.claimAvailable(nextEpoch,rebuilt.catchments().keySet());
        if(!incoming.equals(landing.byDestination()))
            throw new IllegalArgumentException("Pending landing inventory changed");
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:rebuilt.routes())
            nodes.add(new HydrologyFlowGraphV2.Node(node.address(),
                incoming.getOrDefault(node.address(),0.0),
                node.downstream(),node.terminal()));
        var solved=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,solved);
        if(!solved.pendingOutflow().isEmpty() ||
           Math.abs(budget.input()-landing.pendingVolume()) >
             Math.max(1e-12,landing.pendingVolume()*1e-10))
            throw new IllegalArgumentException("Drainage activation water budget mismatch");
        var newState=encode(new BoundState(landing.rebuiltTopology(),bound.lakeState()));
        var checkpoint=HydrologyCheckpointV2.capture(nextEpoch,newState,staged);
        HydrologyCheckpointStoreV2.save(checkpointPath,checkpoint);
        return new Committed(solved,budget,staged,checkpoint,landing);
    }
}
