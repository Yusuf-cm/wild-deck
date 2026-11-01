package com.wilddeck.world;

import java.util.*;

/**
 * Transactional *staging* adapter for imported river sediment. Receipts are
 * claimed on a copy of the snapshot before the river/lake solve; any solver
 * exception leaves the caller's snapshot untouched. The caller must commit
 * the returned snapshot together with all river/lake/terrain changes before
 * acknowledging sender delivery. This adapter performs no I/O or commits.
 */
public final class HydrologySedimentReceiptRoutingV2 {
    private HydrologySedimentReceiptRoutingV2() {}

    public record StagedRiver(HydrologySedimentReceiptInboxV2.Snapshot snapshot,
                              HydrologyRiverSedimentTransportV2.Imported result) {
        public StagedRiver {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(result);
        }
    }

    public record StagedLake(HydrologySedimentReceiptInboxV2.Snapshot snapshot,
                             HydrologyRiverLakeSedimentCouplingV2.ImportedResult result) {
        public StagedLake {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(result);
        }
    }

    private static List<GlobalHydrologyAddressV2.Sample> owned(
            HydrologySphericalLakeBasinsV2.Plan plan) {
        Objects.requireNonNull(plan);
        var addresses = new ArrayList<GlobalHydrologyAddressV2.Sample>();
        for (var node : plan.routes()) addresses.add(node.address());
        return addresses;
    }

    public static StagedRiver stageRiver(
            HydrologySedimentReceiptInboxV2.Snapshot previous,long epoch,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            Map<GlobalHydrologyAddressV2.Sample,Double> capacity) {
        var claimed = HydrologySedimentReceiptInboxV2.claimAvailable(
            previous,epoch,owned(plan));
        var routed = HydrologyRiverSedimentTransportV2.routeWithIncoming(
            plan,eroded,capacity,claimed.imported());
        return new StagedRiver(claimed.snapshot(),routed);
    }

    public static StagedLake stageLake(
            HydrologySedimentReceiptInboxV2.Snapshot previous,long epoch,
            HydrologySphericalLakeBasinsV2.Plan plan,
            HydrologyLakeStateV2.State lakeState,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            Map<GlobalHydrologyAddressV2.Sample,Double> capacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode) {
        var claimed = HydrologySedimentReceiptInboxV2.claimAvailable(
            previous,epoch,owned(plan));
        var routed = HydrologyRiverLakeSedimentCouplingV2.stepWithIncoming(
            plan,lakeState,water,eroded,capacity,claimed.imported(),
            dryBulkDensity,mode);
        return new StagedLake(claimed.snapshot(),routed);
    }
}
