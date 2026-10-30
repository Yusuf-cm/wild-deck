package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in single-writer, quiesced drainage transition with wet lake storage.
 *
 * Both lake plans must come from authoritative, complete, same-resolution
 * terrain snapshots. The route edges are checked against the verified old
 * and rebuilt atlases. This finite-volume remap is NOT a transient hydraulic
 * model, distributed transaction, or seasonal snow/soil migration.
 *
 * The default commit() rejects disappearing wet cells. The explicit
 * commitDisplaced() routes their water from the original vertices through the
 * rebuilt closed drainage network, without modelling flood travel time.
 * Producers must remain quiesced during either activation.
 */
public final class HydrologyWetLakeDrainageActivationV2 {
    private HydrologyWetLakeDrainageActivationV2() {}

    public record Committed(HydrologyLakeNetworkStepV2.Result lakeFlow,
                            HydrologyLakeGeometryTransitionV2.Result remap,
                            HydrologyDrainageLandingPlanV2.Plan landings,
                            HydrologyEpochInboxV2 inbox,
                            HydrologyCheckpointV2.Checkpoint checkpoint) {
        public Committed {
            Objects.requireNonNull(lakeFlow);
            Objects.requireNonNull(remap);
            Objects.requireNonNull(landings);
            Objects.requireNonNull(inbox);
            Objects.requireNonNull(checkpoint);
        }
    }

    /** Bind both verified routing and lake geometry before enabling wet transitions. */
    public static String bind(HydrologySphericalRouteAtlasV2 atlas,
                              HydrologySphericalLakeBasinsV2.Plan plan,
                              String lakeState) {
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(plan);
        verifyRoutes(plan,atlas);
        HydrologyLakeNetworkStepV2.step(plan,HydrologyLakeStateV2.decode(lakeState),
            zeroForcing(plan));
        return HydrologyDrainageActivationV2.encode(
            new HydrologyDrainageActivationV2.BoundState(
                HydrologyDrainageSnapshotIdV2.of(atlas),lakeState,
                HydrologyLakePlanSnapshotIdV2.of(plan)));
    }

    public static Committed commit(Path checkpointPath,long nextEpoch,
            HydrologyCheckpointV2.Checkpoint current,
            HydrologyEpochInboxV2 inbox,
            HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit,
            HydrologySphericalLakeBasinsV2.Plan oldLakes,
            HydrologySphericalLakeBasinsV2.Plan newLakes) throws IOException {
        return commitInternal(checkpointPath,nextEpoch,current,inbox,
            previous,audit,oldLakes,newLakes,false);
    }

    /**
     * Explicit opt-in to route newly exposed old wet-cell water from its
     * original geographic vertex through the verified rebuilt drainage graph.
     * Unlike commit(), this supports disappearance or contraction of wet
     * basins. The remap is instantaneous; no flood travel time is simulated.
     * Producers must remain quiesced and seasonal reservoirs are unsupported.
     */
    public static Committed commitDisplaced(Path checkpointPath,long nextEpoch,
            HydrologyCheckpointV2.Checkpoint current,
            HydrologyEpochInboxV2 inbox,
            HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit,
            HydrologySphericalLakeBasinsV2.Plan oldLakes,
            HydrologySphericalLakeBasinsV2.Plan newLakes) throws IOException {
        return commitInternal(checkpointPath,nextEpoch,current,inbox,
            previous,audit,oldLakes,newLakes,true);
    }

    private static Committed commitInternal(Path checkpointPath,long nextEpoch,
            HydrologyCheckpointV2.Checkpoint current,
            HydrologyEpochInboxV2 inbox,
            HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit,
            HydrologySphericalLakeBasinsV2.Plan oldLakes,
            HydrologySphericalLakeBasinsV2.Plan newLakes,
            boolean allowDisplacement) throws IOException {
        Objects.requireNonNull(checkpointPath);
        Objects.requireNonNull(current);
        Objects.requireNonNull(inbox);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(audit);
        Objects.requireNonNull(oldLakes);
        Objects.requireNonNull(newLakes);
        if(nextEpoch!=Math.addExact(current.epoch(),1))
            throw new IllegalArgumentException("Drainage activation must advance one epoch");
        if(!HydrologyCheckpointV2.restoreInbox(current).snapshot().equals(inbox.snapshot()))
            throw new IllegalArgumentException("Inbox differs from checkpoint");
        var bound=HydrologyDrainageActivationV2.decode(current.worldState());
        if(!bound.topology().equals(HydrologyDrainageSnapshotIdV2.of(previous)) ||
           !bound.topology().equals(audit.previousTopology()))
            throw new IllegalArgumentException("Stale drainage topology");
        var oldState=HydrologyLakeStateV2.decode(bound.lakeState());
        if(oldState.world().startsWith("WD-SNOW-SOIL-1\n"))
            throw new IllegalArgumentException("Seasonal reservoirs require separate reconciliation");
        if(!HydrologyLakePlanSnapshotIdV2.of(oldLakes).equals(bound.lakePlan()))
            throw new IllegalArgumentException("Stale lake geometry checkpoint");
        verifyRoutes(oldLakes,previous);
        verifyRoutes(newLakes,audit.rebuilt());

        // Verify the OLD basin geometry, including exits and connectivity,
        // before treating any lake's stored volume as transferable.
        HydrologyLakeNetworkStepV2.step(oldLakes,oldState,zeroForcing(oldLakes));
        var landings=HydrologyDrainageLandingPlanV2.inspect(
            previous,audit,inbox.snapshot());
        for(var landing:landings.landings())
            if(landing.receipt().epoch()>nextEpoch)
                throw new IllegalArgumentException("Future old-topology receipt remains in flight");

        // Remap old wet columns into new reservoirs; excess is released at
        // each NEW verified spill target, never counted as new rainfall.
        var remap=allowDisplacement ?
            HydrologyWetLakeReconciliationV2.reconcileDisplaced(
                oldState,oldLakes,newLakes) :
            HydrologyWetLakeReconciliationV2.reconcile(
                oldState,oldLakes,newLakes);
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        var incoming=staged.claimAvailable(nextEpoch,audit.rebuilt().catchments().keySet());
        if(!incoming.equals(landings.byDestination()))
            throw new IllegalArgumentException("Pending receipt inventory changed");
        // Never drop a displaced source because it fell outside a new lake.
        // The audit requires identical complete canonical ownership; this
        // assertion also catches future changes to the plan contract.
        var known=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:newLakes.routes())known.add(node.address());
        if(!known.containsAll(remap.overflow().keySet()))
            throw new IllegalArgumentException(
                "Displaced water has no verified rebuilt surface route");
        var enriched=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:newLakes.routes()) {
            double water=incoming.getOrDefault(node.address(),0.0)+
                remap.overflow().getOrDefault(node.address(),0.0);
            if(!Double.isFinite(water)||water<0)
                throw new IllegalArgumentException("Reconciled water overflow");
            enriched.add(new HydrologyFlowGraphV2.Node(
                node.address(),water,node.downstream(),node.terminal()));
        }
        var routed=HydrologyLakeNetworkStepV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(enriched,newLakes.lakes()),
            remap.state(),zeroForcing(newLakes));
        var budget=routed.budget();
        double initial=remap.initialStorage()+landings.pendingVolume();
        double finalWater=budget.finalStorage()+budget.terminalOutflow();
        double tolerance=Math.max(1e-8,initial*1e-10);
        if(!Double.isFinite(initial)||!Double.isFinite(finalWater)||
           budget.evaporation()!=0.0 || budget.additionalInflow()!=0.0 ||
           Math.abs(initial-finalWater)>tolerance)
            throw new IllegalArgumentException("Drainage lake activation lost water");

        var newLakeState=HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State(oldState.world(),routed.state().volumes()));
        var world=HydrologyDrainageActivationV2.encode(
            new HydrologyDrainageActivationV2.BoundState(
                landings.rebuiltTopology(),newLakeState,
                HydrologyLakePlanSnapshotIdV2.of(newLakes)));
        var checkpoint=HydrologyCheckpointV2.capture(nextEpoch,world,staged);
        // Local optimistic freshness only; NOT an interprocess CAS.
        HydrologyCheckpointStoreV2.saveNext(checkpointPath,current,checkpoint);
        return new Committed(routed,remap,landings,staged,checkpoint);
    }

    private static void verifyRoutes(HydrologySphericalLakeBasinsV2.Plan plan,
                                     HydrologySphericalRouteAtlasV2 atlas) {
        if(plan.routes().size()!=atlas.routes().size() ||
           !new HashSet<>(plan.routes()).equals(new HashSet<>(atlas.routes())))
            throw new IllegalArgumentException("Lake plan does not match drainage atlas");
        for(var node:plan.routes())
            if(node.localWater()!=0.0)
                throw new IllegalArgumentException("Unaccounted local water in lake transition");
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
                       HydrologyLakeNetworkStepV2.Forcing> zeroForcing(
            HydrologySphericalLakeBasinsV2.Plan plan) {
        var forcing=new HashMap<GlobalHydrologyAddressV2.Sample,
                                HydrologyLakeNetworkStepV2.Forcing>();
        for(var lake:plan.lakes())
            if(forcing.putIfAbsent(lake.anchor(),
                new HydrologyLakeNetworkStepV2.Forcing(0,0,1))!=null)
                throw new IllegalArgumentException("Duplicate lake anchor");
        return forcing;
    }
}
