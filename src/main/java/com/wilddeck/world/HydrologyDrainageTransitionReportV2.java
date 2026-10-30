package com.wilddeck.world;

import java.util.*;

/**
 * Read-only, deterministic inventory of parked boundary water before a
 * drainage-topology change. It never reroutes, consumes or migrates receipts.
 * Blocked water MUST be reconciled before activating the new atlas.
 */
public final class HydrologyDrainageTransitionReportV2 {
    private HydrologyDrainageTransitionReportV2() {}

    public record Report(String previousTopology,String rebuiltTopology,
                         List<HydrologyTransferLedgerV2.Key> reusable,
                         List<HydrologyTransferLedgerV2.Key> blocked,
                         double reusableVolume,double blockedVolume) {
        public Report {
            Objects.requireNonNull(previousTopology);
            Objects.requireNonNull(rebuiltTopology);
            reusable=List.copyOf(reusable);
            blocked=List.copyOf(blocked);
            if(!Double.isFinite(reusableVolume)||reusableVolume<0||
               !Double.isFinite(blockedVolume)||blockedVolume<0)
                throw new IllegalArgumentException("Invalid pending receipt budget");
        }
        public boolean safeForPendingWater() { return blocked.isEmpty(); }
    }

    private static final Comparator<HydrologyTransferLedgerV2.Key> ORDER=
        Comparator.comparingLong(HydrologyTransferLedgerV2.Key::epoch)
            .thenComparing(HydrologyTransferLedgerV2.Key::from,
                HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(HydrologyTransferLedgerV2.Key::to,
                HydrologyFlowGraphV2.ADDRESS_ORDER);

    public static Report inspect(HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit,
            HydrologyEpochInboxV2.Snapshot inbox) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(audit);
        Objects.requireNonNull(inbox);
        if(!previous.catchments().keySet().equals(audit.rebuilt().catchments().keySet()))
            throw new IllegalArgumentException("Incompatible canonical sample ownership");
        var reusable=new ArrayList<HydrologyTransferLedgerV2.Key>();
        var blocked=new ArrayList<HydrologyTransferLedgerV2.Key>();
        var keys=new ArrayList<>(inbox.receipts().keySet());
        keys.sort(ORDER);
        double safeVolume=0,blockedVolume=0;
        for(var key:keys) {
            if(inbox.delivered().contains(key))continue;
            double volume=inbox.receipts().get(key);
            if(!Double.isFinite(volume)||volume<0)
                throw new IllegalArgumentException("Invalid pending water");
            var from=key.from();
            var to=key.to();
            boolean safe=!audit.affectedSamples().contains(from) &&
                !audit.affectedSamples().contains(to) &&
                previous.catchments().containsKey(from) &&
                previous.catchments().containsKey(to) &&
                Objects.equals(previous.routeAt(from).downstream(),to) &&
                Objects.equals(audit.rebuilt().routeAt(from).downstream(),to) &&
                Objects.equals(previous.catchments().get(from),
                               audit.rebuilt().catchments().get(from)) &&
                Objects.equals(previous.catchments().get(to),
                               audit.rebuilt().catchments().get(to));
            if(safe) {
                reusable.add(key);
                safeVolume+=volume;
            } else {
                blocked.add(key);
                blockedVolume+=volume;
            }
            if(!Double.isFinite(safeVolume)||!Double.isFinite(blockedVolume))
                throw new IllegalArgumentException("Pending receipt budget overflow");
        }
        return new Report(HydrologyDrainageSnapshotIdV2.of(previous),
            HydrologyDrainageSnapshotIdV2.of(audit.rebuilt()),
            reusable,blocked,safeVolume,blockedVolume);
    }
}
