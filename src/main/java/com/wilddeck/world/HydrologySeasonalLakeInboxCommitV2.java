package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in single-writer bridge from durable boundary-water receipts to the
 * seasonal lake/carryover checkpoint. The water inbox and the resulting
 * lake/frontier volumes are replaced in one local atomic checkpoint.
 *
 * Only receipts whose destinations are explicitly declared loaded are claimed.
 * Undelivered receipts retain their original source-edge/epoch identity.
 * This does not coordinate sediment, discover loaded regions, or provide
 * an interprocess lock or distributed transaction.
 */
public final class HydrologySeasonalLakeInboxCommitV2 {
    private HydrologySeasonalLakeInboxCommitV2() {}

    public record Prepared(HydrologySeasonalLakeCarryoverStateV2.State state,
            HydrologyCheckpointV2.Checkpoint checkpoint,
            Map<GlobalHydrologyAddressV2.Sample,Double> claimedM3) {
        public Prepared {
            Objects.requireNonNull(state);
            Objects.requireNonNull(checkpoint);
            claimedM3=Map.copyOf(claimedM3);
        }
    }

    /**
     * Pure preflight: restore the receipt journal, claim only loaded destinations
     * through the current season, and require the candidate to have simulated
     * exactly the previous pending water PLUS newly claimed water.
     *
     * A caller must compute its candidate with the combined carryover map;
     * passing the old map alone is rejected rather than silently losing receipts.
     */
    public static Prepared prepare(HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            HydrologySeasonalLaggedLakeNetworkV2.CarryoverResult candidate,
            Set<GlobalHydrologyAddressV2.Sample> loaded) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(forcing);
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(loaded);
        var previous=HydrologySeasonalLakeCarryoverStateV2.decode(expected.worldState());
        if (previous.season()!=expected.epoch())
            throw new IllegalArgumentException("Lake season and checkpoint epoch differ");
        var owners=new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var address:loaded) {
            Objects.requireNonNull(address);
            if (!HydrologySphericalTopologyV2.isCanonical(address))
                throw new IllegalArgumentException("Noncanonical loaded destination");
            var old=owners.putIfAbsent(
                HydrologyGlobalCatchmentsV2.coordinate(address),address);
            if (old!=null && !old.equals(address))
                throw new IllegalArgumentException("Loaded destinations alias one physical vertex");
        }
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(previous.season(),loaded);
        var carryover=combined(previous.pendingM3(),claimed);
        if (!carryover.equals(candidate.carryoverM3()))
            throw new IllegalArgumentException("Seasonal candidate omits or duplicates received water");
        var augmented=new HydrologySeasonalLakeCarryoverStateV2.State(
            previous.world(),previous.season(),previous.lakeStorageM3(),carryover);
        var next=HydrologySeasonalLakeCarryoverStateV2.advance(
            augmented,forcing,candidate);
        var nextCheckpoint=HydrologyCheckpointV2.capture(
            next.season(),HydrologySeasonalLakeCarryoverStateV2.encode(next),inbox);
        return new Prepared(next,nextCheckpoint,claimed);
    }

    /** Deterministically merge previously conserved water and new receipts. */
    public static Map<GlobalHydrologyAddressV2.Sample,Double> combined(
            Map<GlobalHydrologyAddressV2.Sample,Double> pending,
            Map<GlobalHydrologyAddressV2.Sample,Double> claimed) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(claimed);
        var merged=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry:pending.entrySet()) {
            var address=Objects.requireNonNull(entry.getKey());
            double value=Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(value) || value<0)
                throw new IllegalArgumentException("Invalid pending water");
            merged.put(address,value==0?0.0:value);
        }
        for (var entry:claimed.entrySet()) {
            var address=Objects.requireNonNull(entry.getKey());
            double value=Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(value) || value<0)
                throw new IllegalArgumentException("Invalid received water");
            // Reject receipt or prior frontier water that vanishes entirely
            // when a small and a huge double-valued store converge.
            double sum=HydrologyWaterVolumeAdditionV2.add(
                merged.getOrDefault(address,0.0),value);
            merged.put(address,sum==0?0.0:sum);
        }
        // Enforce canonical addresses and reject different LODs owning one
        // physical vertex, including aliases between old and new water.
        return new HydrologySeasonalLakeCarryoverStateV2.State(
            "validated-frontier",0,Map.of(),merged).pendingM3();
    }

    /**
     * Persist receipt-consumption markers and the updated lake/frontier water
     * together. saveNext rejects a stale expected world/inbox snapshot.
     */
    public static Prepared commitNext(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            HydrologySeasonalLaggedLakeNetworkV2.CarryoverResult candidate,
            Set<GlobalHydrologyAddressV2.Sample> loaded) throws IOException {
        Objects.requireNonNull(file);
        var prepared=prepare(expected,forcing,candidate,loaded);
        HydrologyCheckpointStoreV2.saveNext(
            file,expected,prepared.checkpoint());
        return prepared;
    }

    /**
     * One-step deterministic planning of receipt claims, uniform lake routing,
     * and the complete next checkpoint. No persistent mutation occurs.
     */
    public static Prepared prepareUniform(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            int slices,Set<GlobalHydrologyAddressV2.Sample> loaded) {
        Objects.requireNonNull(expected);
        var previous=HydrologySeasonalLakeCarryoverStateV2.decode(expected.worldState());
        if (previous.season()!=expected.epoch())
            throw new IllegalArgumentException("Lake season and checkpoint epoch differ");
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(previous.season(),Objects.requireNonNull(loaded));
        var carryover=combined(previous.pendingM3(),claimed);
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed,forcing,slices,carryover);
        return prepare(expected,forcing,candidate,loaded);
    }

    /** Uniform forcing, with receipt consumption and lake state committed atomically. */
    public static Prepared commitUniform(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            int slices,Set<GlobalHydrologyAddressV2.Sample> loaded) throws IOException {
        Objects.requireNonNull(file);
        var prepared=prepareUniform(expected,routed,forcing,slices,loaded);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }

    /** Explicit chronological lake forcing, using the same audited receipt bridge. */
    public static Prepared prepareScheduled(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            Map<GlobalHydrologyAddressV2.Sample,
                List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded) {
        Objects.requireNonNull(expected);
        var previous=HydrologySeasonalLakeCarryoverStateV2.decode(expected.worldState());
        if (previous.season()!=expected.epoch())
            throw new IllegalArgumentException("Lake season and checkpoint epoch differ");
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(previous.season(),Objects.requireNonNull(loaded));
        var carryover=combined(previous.pendingM3(),claimed);
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceScheduledWithCarryover(
            routed,forcing,schedules,carryover);
        return prepare(expected,forcing,candidate,loaded);
    }

    /** Chronological forcing, with receipt consumption and lake state committed atomically. */
    public static Prepared commitScheduled(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            Map<GlobalHydrologyAddressV2.Sample,
                List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded) throws IOException {
        Objects.requireNonNull(file);
        var prepared=prepareScheduled(expected,routed,forcing,schedules,loaded);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }


    /**
     * Opt-in reactivation of conserved pending water when authenticated
     * drainage vertices become loaded. A caller-supplied route may move
     * water through loaded vertices into a lake or to the first unloaded
     * frontier, but never into an unaccounted verified ocean mouth.
     * Receipt claims, remapped carryover and lake storage are committed
     * together. The original pending map is not also carried forward.
     */
    public static Prepared prepareReactivated(
            HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            HydrologySeasonalLaggedLakeNetworkV2.CarryoverResult candidate,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(forcing);
        Objects.requireNonNull(candidate);
        var previous=HydrologySeasonalLakeCarryoverStateV2.decode(expected.worldState());
        if (previous.season()!=expected.epoch())
            throw new IllegalArgumentException("Lake season and checkpoint epoch differ");
        if (!Objects.requireNonNull(loaded).containsAll(forcing.keySet()))
            throw new IllegalArgumentException("Cannot advance an unloaded lake");
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(previous.season(),loaded);
        var combined=combined(previous.pendingM3(),claimed);
        var reactivated=HydrologySeasonalPendingReactivationV2.reactivate(
            combined,knownDownstream,loaded,forcing.keySet(),verifiedMouths);
        if (!reactivated.equals(candidate.carryoverM3()))
            throw new IllegalArgumentException("Seasonal candidate omitted or duplicated reactivated water");
        var augmented=new HydrologySeasonalLakeCarryoverStateV2.State(
            previous.world(),previous.season(),previous.lakeStorageM3(),reactivated);
        var next=HydrologySeasonalLakeCarryoverStateV2.advance(
            augmented,forcing,candidate);
        var checkpoint=HydrologyCheckpointV2.capture(
            next.season(),HydrologySeasonalLakeCarryoverStateV2.encode(next),inbox);
        return new Prepared(next,checkpoint,claimed);
    }

    /** Pure, deterministic planning for loaded-route reactivation and uniform lakes. */
    public static Prepared prepareUniformReactivated(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            int slices,Set<GlobalHydrologyAddressV2.Sample> loaded,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream) {
        Objects.requireNonNull(routed);
        var previous=HydrologySeasonalLakeCarryoverStateV2.decode(expected.worldState());
        if (previous.season()!=expected.epoch())
            throw new IllegalArgumentException("Lake season and checkpoint epoch differ");
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(previous.season(),Objects.requireNonNull(loaded));
        var combined=combined(previous.pendingM3(),claimed);
        var mouths=routed.drainage().terminalOutflow().keySet();
        var reactivated=HydrologySeasonalPendingReactivationV2.reactivate(
            combined,knownDownstream,loaded,forcing.keySet(),mouths);
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed,forcing,slices,reactivated);
        return prepareReactivated(expected,forcing,candidate,loaded,knownDownstream,mouths);
    }

    /** Local atomic receipt + reactivated-water + lake checkpoint commit. */
    public static Prepared commitUniformReactivated(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            int slices,Set<GlobalHydrologyAddressV2.Sample> loaded,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream) throws IOException {
        Objects.requireNonNull(file);
        var prepared=prepareUniformReactivated(
            expected,routed,forcing,slices,loaded,knownDownstream);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }

    /** Pure chronological planning with the same reactivated-water audit. */
    public static Prepared prepareScheduledReactivated(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            Map<GlobalHydrologyAddressV2.Sample,
                List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream) {
        Objects.requireNonNull(routed);
        var previous=HydrologySeasonalLakeCarryoverStateV2.decode(expected.worldState());
        if (previous.season()!=expected.epoch())
            throw new IllegalArgumentException("Lake season and checkpoint epoch differ");
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(previous.season(),Objects.requireNonNull(loaded));
        var combined=combined(previous.pendingM3(),claimed);
        var mouths=routed.drainage().terminalOutflow().keySet();
        var reactivated=HydrologySeasonalPendingReactivationV2.reactivate(
            combined,knownDownstream,loaded,forcing.keySet(),mouths);
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceScheduledWithCarryover(
            routed,forcing,schedules,reactivated);
        return prepareReactivated(expected,forcing,candidate,loaded,knownDownstream,mouths);
    }

    /** Chronological reactivation with atomic local water receipt persistence. */
    public static Prepared commitScheduledReactivated(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            Map<GlobalHydrologyAddressV2.Sample,
                List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream) throws IOException {
        Objects.requireNonNull(file);
        var prepared=prepareScheduledReactivated(
            expected,routed,forcing,schedules,loaded,knownDownstream);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }

}
