package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in, single-writer atomic riverbed erosion/deposition checkpoint.
 *
 * A verified, entirely local closed sediment budget changes nonlake river
 * elevations while lake geometry, lake water, water inbox, and historical
 * sediment receipts remain in the SAME guarded checkpoint. It does not
 * acknowledge senders, import sediment, route exported mass, change lake
 * spillways or provide a distributed multi-region transaction.
 */
public final class HydrologyRiverbedCheckpointV2 {
    private HydrologyRiverbedCheckpointV2() {}

    public record Planned(HydrologyCheckpointV2.Checkpoint next,
                          HydrologyRiverbedMassBalanceV2.Preview riverbed) {
        public Planned {
            Objects.requireNonNull(next);
            Objects.requireNonNull(riverbed);
        }
    }

    public static Planned plan(
            HydrologyCheckpointV2.Checkpoint previous,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            String nextWorld,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            HydrologyRiverSedimentTransportV2.Result transport,
            Map<GlobalHydrologyAddressV2.Sample,Double> cellArea,
            double dryBulkDensity, double maximumIncision, double maximumRise) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(seed);
        var sediment = HydrologySedimentCheckpointV2.decode(previous.worldState());
        if (sediment.sediment().lastClaimedEpoch() != previous.epoch() &&
            !(previous.epoch() == 0 &&
              sediment.sediment().lastClaimedEpoch() == -1))
            throw new IllegalArgumentException("Sediment ledger epoch mismatch");
        var waterInbox = HydrologyInboxCodecV2.decode(previous.inboxState());
        if (!waterInbox.delivered().containsAll(waterInbox.receipts().keySet()))
            throw new IllegalArgumentException("Unsettled water requires coupled epoch");

        long epoch = Math.addExact(previous.epoch(),1);
        for (var receipt : sediment.sediment().receipts().keySet())
            if (receipt.arrivalEpoch() <= epoch &&
                !sediment.sediment().delivered().contains(receipt))
                throw new IllegalArgumentException(
                    "Unsettled sediment requires coupled epoch");
        // Close the new sediment epoch without importing or consuming mass.
        // Future receipts remain in the same durable history.
        var claimed = HydrologySedimentReceiptInboxV2.claimAvailable(
            sediment.sediment(),epoch,List.of());
        if (claimed.importedMass() != 0 ||
            !claimed.snapshot().delivered().equals(sediment.sediment().delivered()))
            throw new IllegalStateException("Unexpected riverbed sediment import");

        var restored = HydrologyLakeSedimentSnapshotV2.decode(
            baseline,sediment.world());
        var terrain = HydrologyTerrainOverlayV2.decode(restored.water().world());
        var elevations = HydrologyTerrainOverlayV2.elevations(seed,terrain);
        // A lake checkpoint must never be used to smuggle a mismatched bed
        // into a separate riverbed terrain epoch.
        var seen = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var lake : restored.plan().lakes()) {
            var beds = lake.reservoir().bedElevation();
            for (int i=0;i<lake.cells().size();i++) {
                var cell = lake.cells().get(i);
                if (!seen.add(HydrologyGlobalCatchmentsV2.coordinate(cell)) ||
                    Double.compare(elevations.at(cell),beds[i]) != 0)
                    throw new IllegalArgumentException(
                        "Persisted lake bed disagrees with terrain");
            }
        }
        var riverbed = HydrologyRiverbedMassBalanceV2.plan(
            restored.plan(),terrain,seed,eroded,transport,cellArea,
            dryBulkDensity,maximumIncision,maximumRise);
        var nextTerrain = new HydrologyTerrainOverlayV2.State(
            nextWorld,riverbed.terrain().elevations());
        var lakeState = new HydrologyLakeStateV2.State(
            HydrologyTerrainOverlayV2.encode(nextTerrain),
            restored.water().volumes());
        var lakeSnapshot = HydrologyLakeSedimentSnapshotV2.encode(
            baseline,restored.plan(),lakeState);
        var next = new HydrologyCheckpointV2.Checkpoint(
            epoch,HydrologySedimentCheckpointV2.encode(
                lakeSnapshot,claimed.snapshot()),previous.inboxState());
        return new Planned(next,riverbed);
    }

    /** Persist only after all preflight checks pass; stale writers fail. */
    public static Planned commit(
            Path path,HydrologyCheckpointV2.Checkpoint previous,
            HydrologySphericalLakeBasinsV2.Plan baseline,String nextWorld,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            HydrologyRiverSedimentTransportV2.Result transport,
            Map<GlobalHydrologyAddressV2.Sample,Double> cellArea,
            double dryBulkDensity,double maximumIncision,double maximumRise)
            throws IOException {
        Objects.requireNonNull(path);
        var planned = plan(previous,baseline,nextWorld,seed,eroded,transport,
            cellArea,dryBulkDensity,maximumIncision,maximumRise);
        HydrologyCheckpointStoreV2.saveNext(path,previous,planned.next());
        return planned;
    }
}
