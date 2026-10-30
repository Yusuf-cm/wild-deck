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

        int count=0;
        double total=0;
        for(var entry:inbox.receipts().entrySet()) {
            var key=entry.getKey();
            if(inbox.delivered().contains(key))continue;
            var from=key.from();
            var to=key.to();
            // A receipt is an old edge's water, not free rainfall at its
            // destination. Re-routing it without explicit accounting risks
            // duplication or bypassing a changed lake/catchment.
            if(audit.affectedSamples().contains(from) ||
               audit.affectedSamples().contains(to) ||
               !previous.catchments().containsKey(from) ||
               !previous.catchments().containsKey(to) ||
               !Objects.equals(previous.routeAt(from).downstream(),to) ||
               !Objects.equals(audit.rebuilt().routeAt(from).downstream(),to) ||
               !Objects.equals(previous.catchments().get(from),
                               audit.rebuilt().catchments().get(from)) ||
               !Objects.equals(previous.catchments().get(to),
                               audit.rebuilt().catchments().get(to)))
                throw new IllegalArgumentException(
                    "Pending water crosses changed or unverified drainage");
            double volume=entry.getValue();
            if(!Double.isFinite(volume)||volume<0)
                throw new IllegalArgumentException("Invalid pending water volume");
            total+=volume;
            if(!Double.isFinite(total))
                throw new IllegalArgumentException("Pending water budget overflow");
            count=Math.addExact(count,1);
        }
        return new Verified(count,total);
    }
}
