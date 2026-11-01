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
    private static final String WET_MAGIC="WD-DRAINAGE-ACTIVATION-2";

    public record BoundState(String topology,String lakeState,String lakePlan) {
        public BoundState(String topology,String lakeState) {
            this(topology,lakeState,null);
        }
        public BoundState {
            Objects.requireNonNull(topology);
            Objects.requireNonNull(lakeState);
            if(!topology.matches("[0-9a-f]{64}") ||
               (lakePlan!=null&&!lakePlan.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("Invalid drainage snapshot identity");
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
        var lake=Base64.getEncoder().encodeToString(
            state.lakeState().getBytes(StandardCharsets.UTF_8));
        return state.lakePlan()==null ?
            MAGIC+"\n"+state.topology()+"\n"+lake+"\n" :
            WET_MAGIC+"\n"+state.topology()+"\n"+
                state.lakePlan()+"\n"+lake+"\n";
    }

    public static BoundState decode(String payload) {
        Objects.requireNonNull(payload);
        var parts=payload.split("\n",-1);
        boolean wet=parts.length==5&&WET_MAGIC.equals(parts[0]);
        if(!(wet || (parts.length==4&&MAGIC.equals(parts[0]))) ||
           !parts[parts.length-1].isEmpty())
            throw new IllegalArgumentException("Unsupported drainage checkpoint");
        try {
            var bytes=Base64.getDecoder().decode(parts[wet?3:2]);
            var lake=StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString();
            var result=new BoundState(parts[1],lake,wet?parts[2]:null);
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
        if(bound.lakePlan()!=null)
            throw new IllegalArgumentException("Wet lake geometry requires lake-aware activation");
        var lake=HydrologyLakeStateV2.decode(bound.lakeState());
        if(!lake.volumes().isEmpty())
            throw new IllegalArgumentException("Live lake storage requires reconciliation");
        // Seasonal checkpoints nest their snow/soil stores inside the lake
        // payload. Even an empty seasonal store is not an authorized
        // receipt-only epoch: accepting it would silently bypass the
        // seasonal state machine and its water-budget validation.
        if(lake.world().startsWith("WD-SNOW-SOIL-1\n"))
            throw new IllegalArgumentException(
                "Seasonal snow/soil checkpoint requires coupled reconciliation");

        // A receipt-only transition must not silently discard rainfall or
        // other locally sourced water already present in either atlas. The
        // activation solve deliberately injects ONLY recorded receipt volumes.
        for(var node:previous.routes())
            if(node.localWater()!=0.0)
                throw new IllegalArgumentException(
                    "Old drainage atlas contains unaccounted local source water");
        for(var node:audit.rebuilt().routes())
            if(node.localWater()!=0.0)
                throw new IllegalArgumentException(
                    "New drainage atlas contains unaccounted local source water");

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
        // Catch a stale local runner whose checkpoint was updated by another
        // instance (for example a receipt persisted after this runner opened).
        // This is a defensive freshness check, NOT a cross-process CAS/lock.
        HydrologyCheckpointStoreV2.saveNext(checkpointPath,current,checkpoint);
        return new Committed(solved,budget,staged,checkpoint,landing);
    }
}
