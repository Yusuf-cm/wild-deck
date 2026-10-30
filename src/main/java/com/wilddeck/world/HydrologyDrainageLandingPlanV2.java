package com.wilddeck.world;

import java.util.*;

/**
 * Read-only, fail-closed landing inventory for water dispatched under an OLD
 * verified drainage topology. A receipt has already left its source; after
 * terrain changes its physical landing remains the recorded destination,
 * never the source's newly selected downstream edge.
 *
 * This prepares an exact-once receiving budget but DOES NOT consume receipts,
 * activate a new atlas, reconcile lake storage or authorize a distributed
 * transaction. A future activation must checkpoint both the new topology ID
 * and receipt consumption together.
 */
public final class HydrologyDrainageLandingPlanV2 {
    private HydrologyDrainageLandingPlanV2() {}

    public record Landing(HydrologyTransferLedgerV2.Key receipt,double volume,
                          HydrologyRiverIdentityV2.CatchmentId oldCatchment,
                          HydrologyRiverIdentityV2.CatchmentId newCatchment,
                          GlobalHydrologyAddressV2.Sample onward,
                          boolean affected) {
        public Landing {
            Objects.requireNonNull(receipt);
            Objects.requireNonNull(oldCatchment);
            Objects.requireNonNull(newCatchment);
            if(!Double.isFinite(volume)||volume<0)
                throw new IllegalArgumentException("Invalid landing water");
        }
    }

    public record Plan(String previousTopology,String rebuiltTopology,
                       List<Landing> landings,
                       Map<GlobalHydrologyAddressV2.Sample,Double> byDestination,
                       double pendingVolume) {
        public Plan {
            Objects.requireNonNull(previousTopology);
            Objects.requireNonNull(rebuiltTopology);
            landings=List.copyOf(landings);
            var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            ordered.putAll(byDestination);
            byDestination=Collections.unmodifiableMap(ordered);
            if(!Double.isFinite(pendingVolume)||pendingVolume<0)
                throw new IllegalArgumentException("Invalid pending landing budget");
        }
    }

    private static final Comparator<HydrologyTransferLedgerV2.Key> ORDER=
        Comparator.comparingLong(HydrologyTransferLedgerV2.Key::epoch)
            .thenComparing(HydrologyTransferLedgerV2.Key::from,
                HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(HydrologyTransferLedgerV2.Key::to,
                HydrologyFlowGraphV2.ADDRESS_ORDER);

    public static Plan inspect(HydrologySphericalRouteAtlasV2 previous,
            HydrologySphericalAtlasRebuildAuditV2.Result audit,
            HydrologyEpochInboxV2.Snapshot inbox) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(audit);
        Objects.requireNonNull(inbox);
        var rebuilt=audit.rebuilt();
        if(!previous.catchments().keySet().equals(rebuilt.catchments().keySet()))
            throw new IllegalArgumentException("Incompatible planetary ownership");
        var keys=new ArrayList<>(inbox.receipts().keySet());
        keys.sort(ORDER);
        var landings=new ArrayList<Landing>();
        var destinations=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        double total=0;
        for(var key:keys) {
            if(inbox.delivered().contains(key))continue;
            var oldFrom=previous.routeAt(key.from());
            var oldTo=previous.routeAt(key.to());
            var newTo=rebuilt.routeAt(key.to());
            if(oldFrom.terminal() || !key.to().equals(oldFrom.downstream()) ||
               !oldTo.address().equals(newTo.address()))
                throw new IllegalArgumentException(
                    "Pending receipt did not travel a verified old river edge");
            var oldCatchment=previous.catchments().get(key.to());
            var newCatchment=rebuilt.catchments().get(key.to());
            if(oldCatchment==null||newCatchment==null)
                throw new IllegalArgumentException("Unresolved landing catchment");
            double volume=inbox.receipts().get(key);
            if(!Double.isFinite(volume)||volume<0)
                throw new IllegalArgumentException("Invalid pending landing volume");
            double next=destinations.getOrDefault(key.to(),0.0)+volume;
            total+=volume;
            if(!Double.isFinite(next)||!Double.isFinite(total))
                throw new IllegalArgumentException("Pending landing volume overflow");
            destinations.put(key.to(),next);
            landings.add(new Landing(key,volume,oldCatchment,newCatchment,
                newTo.downstream(),audit.affectedSamples().contains(key.from()) ||
                audit.affectedSamples().contains(key.to())));
        }
        return new Plan(HydrologyDrainageSnapshotIdV2.of(previous),
            HydrologyDrainageSnapshotIdV2.of(rebuilt),landings,destinations,total);
    }
}
