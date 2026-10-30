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
        ensureFresh();
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
            checkpointPath,next,nextWorldState,inbox,loaded,last);
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
            checkpointPath,next,nextWorldState,inbox,loaded,last);
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

    /**
     * Opt-in spherical region loading with longitude wrap, polar clamping,
     * and parked-water destination hints. The provider must use canonical
     * spherical addresses and globally consistent elevation samples.
     * The loaded-region budget is checked before any durable state changes.
     */
    public synchronized HydrologyDurableEpochV2.Routed advanceSphericalPendingRegionsAndQueue(
            String nextWorldState,
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            int level,int radius,int maximumRegions,
            HydrologyRegionSchedulerV2.Provider provider) throws IOException {
        var loaded=HydrologySphericalRegionSchedulerV2.loadWithPending(
            focus,inbox.snapshot(),level,radius,maximumRegions,provider);
        return advanceAndQueueKnownFrontier(nextWorldState,loaded);
    }


    /**
     * Atomically advance a complete, caller-verified spherical lake network
     * together with durable incoming river receipts and lake storage.
     *
     * Receipts for loaded vertices are injected BEFORE lake interception so
     * transferred water cannot bypass a lake or be replayed on restart.
     * Rainfall already represented by a receipt must not also be supplied as
     * localWater or additional forcing. Other-resolution receipts remain
     * parked; this operation is NOT a partial-world or multi-writer solver.
     * A failed solve or checkpoint write leaves the runner unchanged.
     */
    public synchronized HydrologyLakeNetworkStepV2.Result advanceClosedSphericalLakeNetwork(
            String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> forcing)
            throws IOException {
        return advanceClosedSphericalLakeNetworkValidated(
            nextWorld,plan,forcing,ignored->{});
    }

    /**
     * Stage receipt delivery and the lake solve, then validate the complete
     * water budget BEFORE replacing the checkpoint. The package-local hook
     * is for the coupled seasonal adapter and must be side-effect free.
     * Any validator failure leaves the epoch, world and inbox unchanged.
     */
    synchronized HydrologyLakeNetworkStepV2.Result advanceClosedSphericalLakeNetworkValidated(
            String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> forcing,
            java.util.function.Consumer<HydrologyLakeNetworkStepV2.Result> beforeCommit)
            throws IOException {
        Objects.requireNonNull(beforeCommit);
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(forcing);
        long next=Math.addExact(last.epoch(),1);
        var previous=HydrologyLakeStateV2.decode(last.worldState());
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        var addresses=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:plan.routes()) {
            Objects.requireNonNull(node);
            if(!addresses.add(node.address()))
                throw new IllegalArgumentException("Duplicate spherical sample");
        }
        var incoming=staged.claimAvailable(next,addresses);
        var enriched=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:plan.routes()) {
            double water=node.localWater()+incoming.getOrDefault(node.address(),0.0);
            if(!Double.isFinite(water))
                throw new IllegalArgumentException("Lake receipt discharge overflow");
            enriched.add(new HydrologyFlowGraphV2.Node(
                node.address(),water,node.downstream(),node.terminal()));
        }
        var solved=HydrologyLakeNetworkStepV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(enriched,plan.lakes()),
            previous,forcing);
        var updated=new HydrologyLakeStateV2.State(
            nextWorld,solved.state().volumes());
        var result=new HydrologyLakeNetworkStepV2.Result(updated,
            solved.terminalOutflow(),solved.lakeInflow(),
            solved.lakeOverflow(),solved.evaporation(),solved.budget());
        beforeCommit.accept(result);
        var checkpoint=HydrologyCheckpointV2.capture(next,
            HydrologyLakeStateV2.encode(updated),staged);
        HydrologyCheckpointStoreV2.saveNext(checkpointPath,last,checkpoint);
        last=checkpoint;
        inbox=staged;
        return result;
    }


    /**
     * Advance a topology-and-geometry-bound seasonal lake checkpoint while
     * preserving both identities. The caller supplies the authoritative atlas
     * and an enriched runoff plan with identical edges and lake geometry.
     * The budget validator runs before any durable receipt consumption.
     */
    synchronized HydrologyLakeNetworkStepV2.Result advanceBoundClosedSphericalLakeNetworkValidated(
            String nextWorld,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> forcing,
            java.util.function.Consumer<HydrologyLakeNetworkStepV2.Result> beforeCommit)
            throws IOException {
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(forcing);
        Objects.requireNonNull(beforeCommit);
        verifyBoundDrainage(atlas);
        var bound=HydrologyDrainageActivationV2.decode(last.worldState());
        if(!HydrologyLakePlanSnapshotIdV2.of(plan).equals(bound.lakePlan()) ||
           plan.routes().size()!=atlas.routes().size())
            throw new IllegalArgumentException("Bound seasonal lake plan mismatch");
        var addresses=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:plan.routes()) {
            Objects.requireNonNull(node);
            var verified=atlas.routeAt(node.address());
            if(!addresses.add(node.address()) ||
               node.terminal()!=verified.terminal() ||
               !Objects.equals(node.downstream(),verified.downstream()))
                throw new IllegalArgumentException("Changed seasonal drainage edge");
        }
        var previous=HydrologyLakeStateV2.decode(bound.lakeState());
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        long next=Math.addExact(last.epoch(),1);
        var incoming=staged.claimAvailable(next,addresses);
        var enriched=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:plan.routes()) {
            double water=node.localWater()+incoming.getOrDefault(node.address(),0.0);
            if(!Double.isFinite(water))
                throw new IllegalArgumentException("Bound seasonal receipt overflow");
            enriched.add(new HydrologyFlowGraphV2.Node(
                node.address(),water,node.downstream(),node.terminal()));
        }
        var solved=HydrologyLakeNetworkStepV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(enriched,plan.lakes()),
            previous,forcing);
        var updated=new HydrologyLakeStateV2.State(
            nextWorld,solved.state().volumes());
        var result=new HydrologyLakeNetworkStepV2.Result(updated,
            solved.terminalOutflow(),solved.lakeInflow(),solved.lakeOverflow(),
            solved.evaporation(),solved.budget());
        beforeCommit.accept(result);
        var nextBound=HydrologyDrainageActivationV2.encode(
            new HydrologyDrainageActivationV2.BoundState(
                bound.topology(),HydrologyLakeStateV2.encode(updated),
                bound.lakePlan()));
        var checkpoint=HydrologyCheckpointV2.capture(next,nextBound,staged);
        HydrologyCheckpointStoreV2.saveNext(checkpointPath,last,checkpoint);
        last=checkpoint;
        inbox=staged;
        return result;
    }


    /**
     * Read-only preflight of parked cross-region water against a rebuilt
     * drainage atlas. Safe across restart because the checkpointed inbox is
     * the source of truth. Does NOT activate topology, migrate lake storage,
     * or persist a new atlas version.
     */
    public synchronized HydrologyDrainageTransitionGateV2.Verified
            verifyDrainageTransition(
                HydrologySphericalRouteAtlasV2 previous,
                HydrologySphericalAtlasRebuildAuditV2.Result audit) {
        return HydrologyDrainageTransitionGateV2.verifyPending(
            previous,audit,inbox.snapshot());
    }

    /**
     * Inspect which checkpointed, undelivered transfers are safe under a
     * proposed rebuilt atlas. Reports blocked volume without consuming it.
     * This is diagnostics only, not a topology activation transaction.
     */
    public synchronized HydrologyDrainageTransitionReportV2.Report
            inspectDrainageTransition(
                HydrologySphericalRouteAtlasV2 previous,
                HydrologySphericalAtlasRebuildAuditV2.Result audit) {
        return HydrologyDrainageTransitionReportV2.inspect(
            previous,audit,inbox.snapshot());
    }

    /**
     * Inventory old-edge water at its recorded receiving vertex, including
     * destinations whose NEW onward route changed. Read-only: this does not
     * activate topology or consume any checkpointed water.
     */
    public synchronized HydrologyDrainageLandingPlanV2.Plan planDrainageLandings(
            HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit) {
        return HydrologyDrainageLandingPlanV2.inspect(
            previous,audit,inbox.snapshot());
    }

    /**
     * Opt-in receipt-only transition for a quiesced, single-writer, lake-free
     * world. The existing world payload must already be explicitly bound to
     * the old drainage topology using HydrologyDrainageActivationV2.bind().
     * No new rainfall or other reservoirs may be advanced in this epoch.
     * Reopeners must supply and verify the matching atlas independently.
     */
    public synchronized HydrologyDrainageActivationV2.Committed
            activateReceiptOnlyDrainage(
                HydrologySphericalRouteAtlasV2 previous,
                HydrologySphericalAtlasRebuildAuditV2.Result audit)
            throws IOException {
        var committed=HydrologyDrainageActivationV2.commit(
            checkpointPath,Math.addExact(last.epoch(),1),last,inbox,previous,audit);
        last=committed.checkpoint();
        inbox=committed.inbox();
        return committed;
    }

    /**
     * Opt-in wet-lake drainage transition with zero rainfall and quiesced
     * producers. Remaps old stored lake water, routes displaced spill and
     * durable old-edge receipts once, then checkpoints lake storage and the
     * rebuilt topology identity together. Seasonal stores remain unsupported.
     */
    public synchronized HydrologyWetLakeDrainageActivationV2.Committed
            activateWetLakeDrainage(
                HydrologySphericalRouteAtlasV2 previous,
                HydrologySphericalAtlasRebuildAuditV2.Result audit,
                HydrologySphericalLakeBasinsV2.Plan oldLakes,
                HydrologySphericalLakeBasinsV2.Plan newLakes)
            throws IOException {
        var committed=HydrologyWetLakeDrainageActivationV2.commit(
            checkpointPath,Math.addExact(last.epoch(),1),last,inbox,
            previous,audit,oldLakes,newLakes);
        last=committed.checkpoint();
        inbox=committed.inbox();
        return committed;
    }

    /**
     * Explicit opt-in to move water from newly exposed former lake cells
     * along the rebuilt verified surface routes. Preserves the same local
     * single-writer, quiescent and nonseasonal restrictions as wet activation.
     */
    public synchronized HydrologyWetLakeDrainageActivationV2.Committed
            activateDisplacedWetLakeDrainage(
                HydrologySphericalRouteAtlasV2 previous,
                HydrologySphericalAtlasRebuildAuditV2.Result audit,
                HydrologySphericalLakeBasinsV2.Plan oldLakes,
                HydrologySphericalLakeBasinsV2.Plan newLakes)
            throws IOException {
        var committed=HydrologyWetLakeDrainageActivationV2.commitDisplaced(
            checkpointPath,Math.addExact(last.epoch(),1),last,inbox,
            previous,audit,oldLakes,newLakes);
        last=committed.checkpoint();
        inbox=committed.inbox();
        return committed;
    }

    /**
     * Explicit quiescent seasonal transition: preserve existing snow and soil
     * depths at unchanged canonical cells while moving stored lake water and
     * pending receipts through the verified rebuilt drainage network.
     * No seasonal forcing is applied in this transition epoch.
     */
    public synchronized HydrologyWetLakeDrainageActivationV2.Committed
            activateSeasonalDisplacedWetLakeDrainage(
                HydrologySphericalRouteAtlasV2 previous,
                HydrologySphericalAtlasRebuildAuditV2.Result audit,
                HydrologySphericalLakeBasinsV2.Plan oldLakes,
                HydrologySphericalLakeBasinsV2.Plan newLakes)
            throws IOException {
        var committed=HydrologyWetLakeDrainageActivationV2.commitSeasonalDisplaced(
            checkpointPath,Math.addExact(last.epoch(),1),last,inbox,
            previous,audit,oldLakes,newLakes);
        last=committed.checkpoint();
        inbox=committed.inbox();
        return committed;
    }

    /** Fail closed if the caller loaded a drainage atlas unlike the checkpoint. */
    public synchronized void verifyBoundDrainage(
            HydrologySphericalRouteAtlasV2 atlas) {
        var bound=HydrologyDrainageActivationV2.decode(last.worldState());
        if(!bound.topology().equals(HydrologyDrainageSnapshotIdV2.of(atlas)))
            throw new IllegalArgumentException("Atlas does not match durable drainage topology");
    }

    /**
     * Verify both the durable route identity and the lake geometry epoch
     * before resuming a wet-lake world after restart.
     */
    public synchronized void verifyBoundWetLakeDrainage(
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySphericalLakeBasinsV2.Plan plan) {
        verifyBoundDrainage(atlas);
        Objects.requireNonNull(plan);
        var bound=HydrologyDrainageActivationV2.decode(last.worldState());
        if(!HydrologyLakePlanSnapshotIdV2.of(plan).equals(bound.lakePlan()) ||
           plan.routes().size()!=atlas.routes().size() ||
           !new HashSet<>(plan.routes()).equals(new HashSet<>(atlas.routes())))
            throw new IllegalArgumentException("Lake geometry or routes do not match checkpoint");
    }

    /**
     * Opt-in verified receipt entrypoint for a bound drainage checkpoint.
     * Rejects old-topology edges after activation and forged destinations
     * before persisting water. Existing receive() remains an unverified
     * compatibility API; distributed senders need external coordination.
     */
    public synchronized boolean receiveVerifiedDrainage(
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologyTransferLedgerV2.Key key,double flow) throws IOException {
        Objects.requireNonNull(key);
        verifyBoundDrainage(atlas);
        var source=atlas.routeAt(key.from());
        if(source.terminal() || !key.to().equals(source.downstream()))
            throw new IllegalArgumentException("Receipt is not a verified current drainage edge");
        return receive(key,flow);
    }

    /** Return deterministic region hints for receipts still awaiting delivery. */
    public synchronized List<HydrologyRegionSchedulerV2.Region> pendingWaterRegions(){
        return HydrologyRegionSchedulerV2.pendingRegions(inbox.snapshot());
    }

    /** Observe parked versus consumed water without mutating receipt history. */
    public synchronized HydrologyReceiptBudgetV2.Summary receiptBudget(){
        return HydrologyReceiptBudgetV2.summarize(inbox.snapshot());
    }

    public synchronized HydrologyEpochInboxV2.Snapshot inboxSnapshot(){
        return inbox.snapshot();
    }

    /** Fail before mutating a stale runner's receipt state. */
    private void ensureFresh() throws IOException {
        if(!HydrologyCheckpointStoreV2.load(checkpointPath).equals(last))
            throw new IOException("Hydrology runner checkpoint is stale");
    }
}
