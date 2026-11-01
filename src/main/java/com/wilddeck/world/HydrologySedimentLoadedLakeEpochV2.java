package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in loaded-region lifecycle for durable sediment receipt delivery followed
 * by a verified lake epoch. A read-only lake solve is performed BEFORE any
 * sender or receiver checkpoint is changed, so predictable lake failures
 * (silting, invalid terrain, unknown export) do not acknowledge sediment.
 *
 * After preflight, delivery and the lake epoch remain separate local-file
 * transactions. A crash after delivery but before the lake commit is safe to
 * retry using retained sender receipts. A concurrent writer, lost checkpoint,
 * or missing sender history is NOT made safe by this coordinator.
 */
public final class HydrologySedimentLoadedLakeEpochV2 {
    private HydrologySedimentLoadedLakeEpochV2() {}

    public record Committed(HydrologySedimentLoadedNetworkV2.Result delivery,
                            HydrologySedimentCheckpointV2.Committed lake) {
        public Committed {
            Objects.requireNonNull(delivery);
            Objects.requireNonNull(lake);
        }
    }

    public static Committed advance(
            Map<String,Path> senderFiles,
            Map<String,HydrologySedimentLoadedRegionsV2.Receiver> loaded,
            String regionKey,HydrologySphericalLakeBasinsV2.Plan baseline,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode)
            throws IOException {
        return advanceInternal(senderFiles,loaded,regionKey,baseline,water,
            transportCapacity,dryBulkDensity,mode,null,null);
    }

    public static Committed advanceWithTerrain(
            Map<String,Path> senderFiles,
            Map<String,HydrologySedimentLoadedRegionsV2.Receiver> loaded,
            String regionKey,HydrologySphericalLakeBasinsV2.Plan baseline,
            String nextWorld,HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode)
            throws IOException {
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(seed);
        return advanceInternal(senderFiles,loaded,regionKey,baseline,water,
            transportCapacity,dryBulkDensity,mode,nextWorld,seed);
    }

    private static Committed advanceInternal(
            Map<String,Path> senderFiles,
            Map<String,HydrologySedimentLoadedRegionsV2.Receiver> loaded,
            String regionKey,HydrologySphericalLakeBasinsV2.Plan baseline,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode,
            String nextWorld,HydrologyGlobalSlopeRouterV2.Elevations seed)
            throws IOException {
        Objects.requireNonNull(senderFiles);
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(regionKey);
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(water);
        Objects.requireNonNull(transportCapacity);
        Objects.requireNonNull(mode);
        var receiver = Objects.requireNonNull(loaded.get(regionKey),
            "Target sediment region is not loaded");

        // Validate the FULL supplied network, not merely this receiver. In
        // particular this rejects cross-resolution shared-vertex ownership.
        HydrologySedimentLoadedNetworkV2.plan(senderFiles,loaded);
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var vertex : receiver.ownedVertices())
            owned.add(HydrologyGlobalCatchmentsV2.coordinate(vertex));
        var routes = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        var eroded = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : baseline.routes()) {
            var location = HydrologyGlobalCatchmentsV2.coordinate(node.address());
            if (routes.putIfAbsent(location,node.address()) != null ||
                eroded.putIfAbsent(node.address(),0.0) != null ||
                node.localWater() != 0)
                throw new IllegalArgumentException("Invalid zero-runoff lake baseline");
        }
        if (!routes.keySet().containsAll(owned))
            throw new IllegalArgumentException("Loaded receiver is absent from lake drainage");

        Path path = receiver.checkpoint();
        var previous = HydrologyCheckpointStoreV2.load(path);
        var state = HydrologySedimentCheckpointV2.decode(previous.worldState());
        if (state.sediment().lastClaimedEpoch() != previous.epoch() &&
            !(previous.epoch() == 0 && state.sediment().lastClaimedEpoch() == -1))
            throw new IllegalArgumentException("Sediment ledger epoch mismatch");
        var waterInbox = HydrologyInboxCodecV2.decode(previous.inboxState());
        if (!waterInbox.delivered().containsAll(waterInbox.receipts().keySet()))
            throw new IllegalArgumentException("Unsettled water requires a coupled epoch");
        var restored = HydrologyLakeSedimentSnapshotV2.decode(baseline,state.world());

        // Stage exactly the receipts which durable delivery would append.
        // This solves the lake and validates terrain BEFORE acknowledging any
        // sender. A later crash can still leave receipts staged for retry.
        var candidate = state.sediment();
        for (var sender : new TreeMap<>(senderFiles).entrySet()) {
            var outbox = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(sender.getValue()));
            for (var transfer : outbox.receipts().entrySet())
                if (owned.contains(HydrologyGlobalCatchmentsV2.coordinate(
                        transfer.getKey().to())))
                    candidate = HydrologySedimentReceiptInboxV2.receive(
                        candidate,transfer.getKey(),transfer.getValue());
        }
        var staged = HydrologySedimentReceiptRoutingV2.stageLake(candidate,
            Math.addExact(previous.epoch(),1),restored.plan(),restored.water(),
            water,eroded,transportCapacity,dryBulkDensity,mode);
        var result = staged.result().result();
        var river = result.river();
        var lake = result.lake();
        if (river.budget().riverDeposited() != 0 ||
            river.budget().terminalExported() != 0 ||
            river.budget().pendingExported() != 0 ||
            lake.budget().sedimentExported() != 0)
            throw new IllegalArgumentException("Unverified sediment export or riverbed change");
        if (seed != null)
            HydrologyLakeSedimentTerrainBindingV2.updateWorld(nextWorld,seed,
                restored.plan(),lake.updatedPlan(),restored.water().world());

        // Only the target loaded receiver is mutated. Other loaded regions
        // are validated above but advanced independently by their own epochs.
        var delivered = HydrologySedimentLoadedNetworkV2.deliver(
            senderFiles,Map.of(regionKey,receiver));
        var current = HydrologyCheckpointStoreV2.load(path);
        var committed = seed == null
            ? HydrologySedimentCheckpointV2.advanceLake(path,current,baseline,
                water,transportCapacity,dryBulkDensity,mode)
            : HydrologySedimentCheckpointV2.advanceLakeWithTerrain(path,current,
                baseline,nextWorld,seed,water,transportCapacity,dryBulkDensity,mode);
        return new Committed(delivered,committed);
    }
}
