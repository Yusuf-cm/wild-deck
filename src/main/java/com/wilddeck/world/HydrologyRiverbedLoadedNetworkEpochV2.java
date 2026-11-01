package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in, deterministic batch of independently durable, closed-budget riverbed
 * epochs. Every region is route-verified and every supplied sender/receiver
 * checkpoint is quiescent before the first write. The caller supplies the
 * ORIGINAL checksummed checkpoint for each region, allowing exact-state
 * idempotent recovery after a partially committed batch.
 *
 * This is NOT a distributed transaction, global lock, cross-region sediment
 * import/export, or drainage-topology migration. The caller must quiesce all
 * producers and serialize writers across the complete supplied network.
 */
public final class HydrologyRiverbedLoadedNetworkEpochV2 {
    private HydrologyRiverbedLoadedNetworkEpochV2() {}

    public record RegionStep(
            HydrologySedimentLoadedRegionsV2.Receiver receiver,
            HydrologyCheckpointV2.Checkpoint expectedPrevious,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            String nextWorld,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            HydrologyRiverSedimentTransportV2.Result transport,
            Map<GlobalHydrologyAddressV2.Sample,Double> cellArea,
            double dryBulkDensity, double maximumIncision, double maximumRise) {
        public RegionStep {
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(expectedPrevious);
            Objects.requireNonNull(baseline);
            Objects.requireNonNull(nextWorld);
            Objects.requireNonNull(seed);
            eroded = Map.copyOf(Objects.requireNonNull(eroded));
            Objects.requireNonNull(transport);
            cellArea = Map.copyOf(Objects.requireNonNull(cellArea));
            if (!Double.isFinite(dryBulkDensity) || dryBulkDensity <= 0 ||
                !Double.isFinite(maximumIncision) || maximumIncision <= 0 ||
                !Double.isFinite(maximumRise) || maximumRise <= 0)
                throw new IllegalArgumentException("Invalid riverbed physical parameters");
        }
    }

    public record Plan(
            long targetEpoch,
            Map<String,HydrologyRiverbedCheckpointV2.Planned> pending,
            Set<String> completed,
            HydrologySedimentTopologyGateV2.Report before,
            double erodedMass, double depositedMass) {
        public Plan {
            if (targetEpoch < 1) throw new IllegalArgumentException("Invalid target epoch");
            pending = Collections.unmodifiableMap(new TreeMap<>(pending));
            completed = Collections.unmodifiableSet(new TreeSet<>(completed));
            Objects.requireNonNull(before);
            if (!Double.isFinite(erodedMass) || erodedMass < 0 ||
                !Double.isFinite(depositedMass) || depositedMass < 0)
                throw new IllegalArgumentException("Invalid riverbed network mass");
        }
    }

    public record Result(
            long targetEpoch,
            Map<String,HydrologyRiverbedCheckpointV2.Planned> advanced,
            Set<String> alreadyCompleted,
            HydrologySedimentTopologyGateV2.Report after) {
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
                throw new IllegalArgumentException("Invalid riverbed region key");
            sorted.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        if (sorted.isEmpty()) throw new IllegalArgumentException("No riverbed regions");
        return sorted;
    }

    private record HaloHeight(GlobalHydrologyAddressV2.Sample sample,
                              double before, double after) {}

    /**
     * Every overlapping one-ring halo must agree on the physical elevation
     * both before and after the proposed batch. Independently sampled terrain
     * or a neighbor's unseen bed edit cannot silently invalidate a route.
     * Cross-resolution aliases fail closed rather than being interpolated.
     */
    private static void verifySharedHalos(
            Map<String,RegionStep> steps,
            Map<String,HydrologyRiverbedCheckpointV2.Planned> previews) {
        var seen = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,HaloHeight>();
        for (var entry : steps.entrySet()) {
            var step = entry.getValue();
            var envelope = HydrologySedimentCheckpointV2.decode(
                step.expectedPrevious().worldState());
            var lake = HydrologyLakeSedimentSnapshotV2.decode(
                step.baseline(),envelope.world());
            var previous = HydrologyTerrainOverlayV2.decode(lake.water().world());
            var before = HydrologyTerrainOverlayV2.elevations(step.seed(),previous);
            var after = HydrologyTerrainOverlayV2.elevations(
                step.seed(),previews.get(entry.getKey()).riverbed().terrain());
            var halo = new TreeSet<GlobalHydrologyAddressV2.Sample>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for (var node : step.baseline().routes()) {
                halo.add(node.address());
                halo.addAll(HydrologySphericalTopologyV2.neighbors(node.address()));
            }
            for (var sample : halo) {
                double oldHeight = before.at(sample);
                double newHeight = after.at(sample);
                if (!Double.isFinite(oldHeight) || !Double.isFinite(newHeight))
                    throw new IllegalArgumentException("Nonfinite shared riverbed halo");
                var current = new HaloHeight(sample,oldHeight,newHeight);
                var earlier = seen.putIfAbsent(
                    HydrologyGlobalCatchmentsV2.coordinate(sample),current);
                if (earlier != null &&
                    (!earlier.sample().equals(sample) ||
                     Double.compare(earlier.before(),oldHeight) != 0 ||
                     Double.compare(earlier.after(),newHeight) != 0))
                    throw new IllegalArgumentException(
                        "Inconsistent shared riverbed halo or uncoordinated neighbor edit");
            }
        }
    }

    /**
     * Read-only whole-network preflight. No receiver is changed if any region
     * has a bad route, stale checkpoint, overlapping ownership or open receipt.
     */
    public static Plan plan(Map<String,Path> senderFiles,
                            Map<String,RegionStep> steps,long targetEpoch)
            throws IOException {
        Objects.requireNonNull(senderFiles);
        if (targetEpoch < 1) throw new IllegalArgumentException("Invalid target epoch");
        var ordered = ordered(steps);
        var loaded = new TreeMap<String,HydrologySedimentLoadedRegionsV2.Receiver>();
        for (var entry : ordered.entrySet())
            loaded.put(entry.getKey(),entry.getValue().receiver());
        var gate = HydrologySedimentTopologyGateV2.requireQuiescent(senderFiles,loaded);

        var pending = new TreeMap<String,HydrologyRiverbedCheckpointV2.Planned>();
        var completed = new TreeSet<String>();
        var previews = new TreeMap<String,HydrologyRiverbedCheckpointV2.Planned>();
        BigDecimal erodedMass = BigDecimal.ZERO, depositedMass = BigDecimal.ZERO;
        for (var entry : ordered.entrySet()) {
            String name = entry.getKey();
            RegionStep step = entry.getValue();
            if (step.expectedPrevious().epoch() != targetEpoch - 1)
                throw new IllegalArgumentException("Riverbed baseline epoch mismatch");

            var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
            for (var vertex : step.receiver().ownedVertices())
                owned.add(HydrologyGlobalCatchmentsV2.coordinate(vertex));
            // A routing graph may include a read-only halo, but every physical
            // vertex losing or receiving sediment must belong to THIS region.
            for (var source : step.eroded().entrySet())
                if (source.getValue() != 0 &&
                    !owned.contains(HydrologyGlobalCatchmentsV2.coordinate(source.getKey())))
                    throw new IllegalArgumentException("Eroded riverbed outside region ownership");
            for (var deposit : step.transport().riverDeposited().entrySet())
                if (deposit.getValue() != 0 &&
                    !owned.contains(HydrologyGlobalCatchmentsV2.coordinate(deposit.getKey())))
                    throw new IllegalArgumentException("Deposited riverbed outside region ownership");

            var preview = HydrologyRiverbedCheckpointV2.planRouteVerified(
                step.expectedPrevious(),step.baseline(),step.nextWorld(),
                step.seed(),step.eroded(),step.transport(),step.cellArea(),
                step.dryBulkDensity(),step.maximumIncision(),step.maximumRise());
            if (preview.next().epoch() != targetEpoch)
                throw new IllegalArgumentException("Riverbed preview epoch mismatch");
            previews.put(name,preview);
            var actual = HydrologyCheckpointStoreV2.load(step.receiver().checkpoint());
            if (actual.equals(step.expectedPrevious())) {
                pending.put(name,preview);
            } else if (actual.equals(preview.next())) {
                completed.add(name);
            } else {
                throw new IOException("Riverbed checkpoint changed or replay parameters differ: " + name);
            }
            erodedMass = erodedMass.add(BigDecimal.valueOf(preview.riverbed().erodedMass()));
            depositedMass = depositedMass.add(BigDecimal.valueOf(preview.riverbed().depositedMass()));
        }
        verifySharedHalos(ordered,previews);
        if (erodedMass.compareTo(depositedMass) != 0 ||
            !Double.isFinite(erodedMass.doubleValue()) ||
            !Double.isFinite(depositedMass.doubleValue()))
            throw new IllegalArgumentException("Unbalanced riverbed network sediment");
        return new Plan(targetEpoch,pending,completed,gate,
            erodedMass.doubleValue(),depositedMass.doubleValue());
    }

    /**
     * Commit pending regions in sorted order. Retry with the SAME original
     * expectedPrevious checkpoints and inputs after interruption. Completed
     * regions must match the exact previously planned checkpoint, not merely
     * an epoch number or a caller-supplied world name.
     */
    public static Result advance(Map<String,Path> senderFiles,
                                 Map<String,RegionStep> steps,long targetEpoch)
            throws IOException {
        var ordered = ordered(steps);
        var preflight = plan(senderFiles,ordered,targetEpoch);
        var advanced = new TreeMap<String,HydrologyRiverbedCheckpointV2.Planned>();
        for (var name : preflight.pending().keySet()) {
            var step = ordered.get(name);
            var committed = HydrologyRiverbedCheckpointV2.commitRouteVerified(
                step.receiver().checkpoint(),step.expectedPrevious(),
                step.baseline(),step.nextWorld(),step.seed(),step.eroded(),
                step.transport(),step.cellArea(),step.dryBulkDensity(),
                step.maximumIncision(),step.maximumRise());
            if (!committed.next().equals(preflight.pending().get(name).next()))
                throw new IllegalStateException("Riverbed preview changed during commit");
            advanced.put(name,committed);
        }
        var loaded = new TreeMap<String,HydrologySedimentLoadedRegionsV2.Receiver>();
        for (var entry : ordered.entrySet())
            loaded.put(entry.getKey(),entry.getValue().receiver());
        var after = HydrologySedimentTopologyGateV2.requireQuiescent(senderFiles,loaded);
        return new Result(targetEpoch,advanced,preflight.completed(),after);
    }
}
