package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in deterministic lake-epoch scheduler for a caller-supplied COMPLETE
 * loaded region set and durable sediment sender histories.
 *
 * Every pending region is simulated and terrain-validated BEFORE the first
 * checkpoint write. Regions already at the requested epoch are skipped on
 * retry; a crash after an earlier region committed cannot advance it twice.
 * Delivery and lake commits remain separate per-region file transactions,
 * not a globally atomic operation. Callers must serialize concurrent writers.
 */
public final class HydrologySedimentLoadedLakeNetworkEpochV2 {
    private HydrologySedimentLoadedLakeNetworkEpochV2() {}

    public record RegionStep(
            HydrologySedimentLoadedRegionsV2.Receiver receiver,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            String nextWorld,HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> capacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode) {
        public RegionStep {
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(baseline);
            Objects.requireNonNull(nextWorld);
            Objects.requireNonNull(seed);
            water = Map.copyOf(Objects.requireNonNull(water));
            capacity = Map.copyOf(Objects.requireNonNull(capacity));
            Objects.requireNonNull(mode);
            if (!Double.isFinite(dryBulkDensity) || dryBulkDensity <= 0)
                throw new IllegalArgumentException("Invalid lake sediment density");
        }
    }

    public record Plan(long targetEpoch,
                       Map<String,HydrologySedimentLoadedLakeEpochV2.Preview> pending,
                       Set<String> completed,
                       HydrologySedimentLoadedNetworkV2.Plan delivery) {
        public Plan {
            if (targetEpoch < 1) throw new IllegalArgumentException("Invalid target epoch");
            pending = Collections.unmodifiableMap(new TreeMap<>(pending));
            completed = Collections.unmodifiableSet(new TreeSet<>(completed));
            Objects.requireNonNull(delivery);
        }
    }

    public record Result(long targetEpoch,
                         Map<String,HydrologySedimentLoadedLakeEpochV2.Committed> advanced,
                         Set<String> alreadyCompleted,
                         HydrologySedimentCrossRegionAuditV2.Report after) {
        public Result {
            advanced = Collections.unmodifiableMap(new TreeMap<>(advanced));
            alreadyCompleted = Collections.unmodifiableSet(new TreeSet<>(alreadyCompleted));
            Objects.requireNonNull(after);
        }
    }

    private static TreeMap<String,RegionStep> ordered(Map<String,RegionStep> steps) {
        Objects.requireNonNull(steps);
        var sorted = new TreeMap<String,RegionStep>();
        for (var entry : steps.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid loaded lake region key");
            sorted.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        if (sorted.isEmpty()) throw new IllegalArgumentException("No loaded lake regions");
        return sorted;
    }

    /** Read-only all-region preflight; rejects predictable failures before writes. */
    public static Plan plan(Map<String,Path> senders,
                            Map<String,RegionStep> steps,long targetEpoch)
            throws IOException {
        Objects.requireNonNull(senders);
        if (targetEpoch < 1) throw new IllegalArgumentException("Invalid target epoch");
        var ordered = ordered(steps);
        var loaded = new TreeMap<String,HydrologySedimentLoadedRegionsV2.Receiver>();
        for (var entry : ordered.entrySet())
            loaded.put(entry.getKey(),entry.getValue().receiver());
        var delivery = HydrologySedimentLoadedNetworkV2.plan(senders,loaded);
        var pending = new TreeMap<String,HydrologySedimentLoadedLakeEpochV2.Preview>();
        var completed = new TreeSet<String>();
        for (var entry : ordered.entrySet()) {
            var key = entry.getKey();
            var step = entry.getValue();
            var checkpoint = HydrologyCheckpointStoreV2.load(step.receiver().checkpoint());
            if (checkpoint.epoch() == targetEpoch) {
                var sediment = HydrologySedimentCheckpointV2.decode(
                    checkpoint.worldState()).sediment();
                if (sediment.lastClaimedEpoch() != targetEpoch)
                    throw new IllegalArgumentException("Completed lake ledger epoch mismatch");
                for (var receipt : sediment.receipts().keySet())
                    if (receipt.arrivalEpoch() <= targetEpoch &&
                        !sediment.delivered().contains(receipt))
                        throw new IllegalArgumentException("Incomplete lake epoch receipt consumption");
                var water = HydrologyInboxCodecV2.decode(checkpoint.inboxState());
                if (!water.delivered().containsAll(water.receipts().keySet()))
                    throw new IllegalArgumentException("Unsettled water in completed lake epoch");
                completed.add(key);
            } else if (checkpoint.epoch() == targetEpoch-1) {
                var preview = HydrologySedimentLoadedLakeEpochV2.previewWithTerrain(
                    senders,loaded,key,step.baseline(),step.nextWorld(),step.seed(),
                    step.water(),step.capacity(),step.dryBulkDensity(),step.mode());
                if (preview.nextEpoch() != targetEpoch)
                    throw new IllegalArgumentException("Lake preview epoch mismatch");
                pending.put(key,preview);
            } else {
                throw new IllegalArgumentException("Loaded lake region outside requested epoch");
            }
        }
        return new Plan(targetEpoch,pending,completed,delivery);
    }

    /**
     * Sorted region commits with idempotent epoch-level retry. An interrupted
     * multi-region operation may leave earlier regions committed; rerun with
     * the SAME targetEpoch and complete sender histories to finish the rest.
     */
    public static Result advance(Map<String,Path> senders,
                                 Map<String,RegionStep> steps,long targetEpoch)
            throws IOException {
        var ordered = ordered(steps);
        var planned = plan(senders,ordered,targetEpoch);
        var loaded = new TreeMap<String,HydrologySedimentLoadedRegionsV2.Receiver>();
        for (var entry : ordered.entrySet())
            loaded.put(entry.getKey(),entry.getValue().receiver());
        var committed = new TreeMap<String,HydrologySedimentLoadedLakeEpochV2.Committed>();
        for (var key : planned.pending().keySet()) {
            var step = ordered.get(key);
            var result = HydrologySedimentLoadedLakeEpochV2.advanceWithTerrain(
                senders,loaded,key,step.baseline(),step.nextWorld(),step.seed(),
                step.water(),step.capacity(),step.dryBulkDensity(),step.mode());
            if (result.lake().checkpoint().epoch() != targetEpoch)
                throw new IllegalStateException("Unexpected committed lake epoch");
            committed.put(key,result);
        }
        var paths = new TreeMap<String,Path>();
        for (var entry : ordered.entrySet())
            paths.put(entry.getKey(),entry.getValue().receiver().checkpoint());
        var audit = HydrologySedimentCrossRegionAuditV2.reconcileAllDurable(senders,paths);
        return new Result(targetEpoch,committed,planned.completed(),audit);
    }
}
