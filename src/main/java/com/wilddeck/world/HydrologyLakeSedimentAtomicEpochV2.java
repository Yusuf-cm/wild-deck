package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Atomically commits sediment-altered lake geometry, retained water and
 * delivered boundary receipts in the existing single-writer checkpoint.
 *
 * The caller supplies a closed, verified, zero-runoff baseline and the next
 * season's runoff on EXACTLY those same routes. The previous geometry and
 * storage are restored from the durable snapshot, not from caller guesses.
 * The old spill topology remains fixed; complete silting fails closed.
 *
 * This does not discover new drainage, authenticate external transfer
 * provenance, persist authoritative terrain elevations or route exported
 * sediment. Those require separate verified transitions.
 */
public final class HydrologyLakeSedimentAtomicEpochV2 {
    private HydrologyLakeSedimentAtomicEpochV2() {}

    public record Committed(HydrologyCheckpointV2.Checkpoint checkpoint,
                            HydrologyEpochInboxV2 inbox,
                            HydrologyLakeSedimentNetworkV2.Result result) {
        public Committed {
            Objects.requireNonNull(checkpoint);
            Objects.requireNonNull(inbox);
            Objects.requireNonNull(result);
        }
    }

    public static Committed commit(
            Path checkpointPath, HydrologyCheckpointV2.Checkpoint previous,
            HydrologyEpochInboxV2 inbox, String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            Collection<HydrologyFlowGraphV2.Node> seasonalRoutes,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeSedimentNetworkV2.Sediment> sediment,
            SeasonalLakeEvolutionV2.EvaporationMode mode) throws IOException {
        return commitInternal(checkpointPath,previous,inbox,nextWorld,baseline,
            seasonalRoutes,water,sediment,mode,null);
    }

    /** Persist lake sediment, water and an authoritative terrain overlay together. */
    public static Committed commitWithTerrain(
            Path checkpointPath, HydrologyCheckpointV2.Checkpoint previous,
            HydrologyEpochInboxV2 inbox, String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            Collection<HydrologyFlowGraphV2.Node> seasonalRoutes,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeSedimentNetworkV2.Sediment> sediment,
            SeasonalLakeEvolutionV2.EvaporationMode mode) throws IOException {
        Objects.requireNonNull(seed);
        return commitInternal(checkpointPath,previous,inbox,nextWorld,baseline,
            seasonalRoutes,water,sediment,mode,seed);
    }

    private static Committed commitInternal(
            Path checkpointPath, HydrologyCheckpointV2.Checkpoint previous,
            HydrologyEpochInboxV2 inbox, String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            Collection<HydrologyFlowGraphV2.Node> seasonalRoutes,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeSedimentNetworkV2.Sediment> sediment,
            SeasonalLakeEvolutionV2.EvaporationMode mode,
            HydrologyGlobalSlopeRouterV2.Elevations seed) throws IOException {
        Objects.requireNonNull(checkpointPath);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(inbox);
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(seasonalRoutes);
        Objects.requireNonNull(water);
        Objects.requireNonNull(sediment);
        Objects.requireNonNull(mode);

        var original = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : baseline.routes()) {
            Objects.requireNonNull(node);
            if (node.localWater() != 0 ||
                original.putIfAbsent(node.address(), node) != null)
                throw new IllegalArgumentException(
                    "Baseline requires unique zero-runoff source vertices");
        }
        if (original.isEmpty())
            throw new IllegalArgumentException("Empty lake drainage baseline");
        var seasonal = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : seasonalRoutes) {
            Objects.requireNonNull(node);
            var expected = original.get(node.address());
            if (expected == null ||
                !Objects.equals(expected.downstream(), node.downstream()) ||
                expected.terminal() != node.terminal() ||
                seasonal.putIfAbsent(node.address(), node) != null)
                throw new IllegalArgumentException(
                    "Seasonal runoff changes verified river topology or ownership");
        }
        if (!seasonal.keySet().equals(original.keySet()))
            throw new IllegalArgumentException("Missing seasonal river source");

        // A failed decode, solve or save must not consume any real inbox water.
        var restored = HydrologyLakeSedimentSnapshotV2.decode(
            baseline, previous.worldState());
        var staged = HydrologyEpochInboxV2.restore(inbox.snapshot());
        long next = Math.addExact(previous.epoch(), 1);
        var incoming = staged.claimAvailable(next, original.keySet());
        var enriched = new ArrayList<HydrologyFlowGraphV2.Node>(seasonal.size());
        for (var node : seasonal.values()) {
            double volume = node.localWater() +
                incoming.getOrDefault(node.address(), 0.0);
            if (!Double.isFinite(volume))
                throw new IllegalArgumentException("Imported water volume overflow");
            enriched.add(new HydrologyFlowGraphV2.Node(
                node.address(), volume, node.downstream(), node.terminal()));
        }
        var advanced = HydrologyLakeSedimentNetworkV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(enriched,
                restored.plan().lakes()), restored.water(), water, sediment, mode);
        String committedWorld = seed == null ? nextWorld :
            HydrologyLakeSedimentTerrainBindingV2.updateWorld(
                nextWorld, seed, restored.plan(), advanced.updatedPlan(),
                restored.water().world());
        var nextWater = new HydrologyLakeStateV2.State(
            committedWorld, advanced.water().state().volumes());
        var payload = HydrologyLakeSedimentSnapshotV2.encode(
            baseline, advanced.updatedPlan(), nextWater);
        var checkpoint = HydrologyCheckpointV2.capture(next, payload, staged);
        HydrologyCheckpointStoreV2.saveNext(checkpointPath, previous, checkpoint);

        var oldResult = advanced.water();
        var committedWater = new HydrologyLakeNetworkStepV2.Result(
            nextWater, oldResult.terminalOutflow(), oldResult.lakeInflow(),
            oldResult.lakeOverflow(), oldResult.evaporation(), oldResult.budget());
        var committedResult = new HydrologyLakeSedimentNetworkV2.Result(
            advanced.updatedPlan(), committedWater, advanced.displaced(),
            advanced.deposited(), advanced.exported(), advanced.budget());
        return new Committed(checkpoint, staged, committedResult);
    }
}
