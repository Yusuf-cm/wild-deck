package com.wilddeck.world;

import java.util.*;

/**
 * Fail-closed preflight for activating a rebuilt spherical drainage snapshot
 * while durable cross-region transfers may still be parked.
 *
 * Only undelivered receipts are in flight. Every such receipt must refer to
 * the same verified edge in BOTH snapshots and must lie outside the audit's
 * conservative upstream invalidation. Delivered receipts stay in the ledger
 * for replay detection and must never be injected again.
 *
 * This does not migrate lake volumes, settled world water, or change the
 * checkpoint's atlas identity. Callers must separately reconcile those
 * states and commit their new atlas/world state atomically before activation.
 */
public final class HydrologyDrainageTransitionGateV2 {
    private HydrologyDrainageTransitionGateV2() {}

    public record Verified(int pendingReceipts,double pendingVolume) {
        public Verified {
            if(pendingReceipts<0 || !Double.isFinite(pendingVolume) || pendingVolume<0)
                throw new IllegalArgumentException("Invalid transition receipt budget");
        }
    }

    public static Verified verifyPending(
            HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit,
            HydrologyEpochInboxV2.Snapshot inbox) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(audit);
        Objects.requireNonNull(inbox);
        if(!previous.catchments().keySet().equals(audit.rebuilt().catchments().keySet()))
            throw new IllegalArgumentException("Incompatible drainage ownership");

        // One authoritative classification prevents preflight and diagnostic
        // inventory from disagreeing about changed edges or catchments.
        var report=HydrologyDrainageTransitionReportV2.inspect(
            previous,audit,inbox);
        if(!report.safeForPendingWater())
            throw new IllegalArgumentException(
                "Pending water crosses changed or unverified drainage");
        return new Verified(report.reusable().size(),report.reusableVolume());
    }
}
