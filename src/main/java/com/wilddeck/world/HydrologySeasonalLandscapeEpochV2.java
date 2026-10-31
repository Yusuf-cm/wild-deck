package com.wilddeck.world;

import java.io.IOException;
import java.util.*;

/**
 * Opt-in atomic seasonal transition for a CLOSED, lake-free terrain window.
 *
 * Recomputes snow/soil runoff, incision, sediment export and post-erosion
 * drainage from the last committed landscape. Only verified outlets may
 * terminate flow. Rejects ponded depressions and all undelivered receipts:
 * neither lake storage nor old-topology boundary water can be safely migrated
 * by this restricted transition.
 *
 * The world payload and consumed epoch are committed by the existing local
 * single-writer checkpoint runner. This does not activate global topology,
 * migrate lake storage, or implement distributed atomicity.
 */
public final class HydrologySeasonalLandscapeEpochV2 {
    private HydrologySeasonalLandscapeEpochV2() {}

    public record Committed(HydrologySeasonalLandscapeStateV2.Prepared prepared,
                            HydrologyFlowGraphV2.Result flow,
                            HydrologyConservationV2.Budget budget,
                            int queuedTransfers) {
        public Committed {
            Objects.requireNonNull(prepared);
            Objects.requireNonNull(flow);
            Objects.requireNonNull(budget);
            if (queuedTransfers < 0)
                throw new IllegalArgumentException("Invalid queued transfer count");
        }
    }

    public static Committed advanceClosedWindow(
            HydrologyWorldRunnerV2 runner,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets)
            throws IOException {
        return advanceVerifiedFrontier(runner, addresses, width, height, seed,
            weather, radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, Map.of());
    }

    /**
     * Optionally export a verified outlet to an adjacent, strictly lower
     * canonical sample outside this window. The resulting transfer is parked
     * in the same durable epoch checkpoint as the updated seasonal landscape.
     * Only a known one-step exit is supported; unknown downstreams and
     * in-flight old-topology receipts remain forbidden.
     */
    public static Committed advanceVerifiedFrontier(
            HydrologyWorldRunnerV2 runner,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets,
            Map<Integer,GlobalHydrologyAddressV2.Sample> externalExits)
            throws IOException {
        synchronized (Objects.requireNonNull(runner)) {
            String oldWorld = runner.worldState();
            long oldEpoch = runner.epoch();
            var oldInbox = runner.inboxSnapshot();
            // Even future-dated receipts must remain bound to their original
            // topology. This restricted mode has no topology migration.
            for (var key : oldInbox.receipts().keySet())
                if (!oldInbox.delivered().contains(key))
                    throw new IllegalStateException(
                        "Seasonal terrain activation requires no pending boundary water");

            var previous = HydrologySeasonalLandscapeStateV2.decode(oldWorld);
            var prepared = HydrologySeasonalLandscapeStateV2.prepare(
                previous, addresses, width, height, seed, weather,
                radiusMeters, sedimentDensity, erosionStrength, maximumCut,
                transportCapacity, verifiedOutlets);
            var erosion = prepared.preview().erosion();
            var landscape = erosion.terrain().landscape();
            var spill = landscape.drainage().spillTo();
            var surface = landscape.drainage().waterSurface();
            var terrain = landscape.terrain();
            var outlets = Set.copyOf(verifiedOutlets);
            var exits = Map.copyOf(externalExits);
            var owned = Set.copyOf(addresses);
            var elevation = HydrologyTerrainOverlayV2.elevations(
                seed, prepared.state().terrain());
            for (var exit : exits.entrySet()) {
                int index = exit.getKey();
                if (!outlets.contains(index))
                    throw new IllegalArgumentException("Unverified external exit");
                var from = addresses.get(index);
                var to = exit.getValue();
                if (owned.contains(to) ||
                    !HydrologySphericalTopologyV2.isCanonical(to) ||
                    !HydrologySphericalTopologyV2.adjacent(from, to))
                    throw new IllegalArgumentException("Invalid external exit edge");
                double downstreamHeight = elevation.at(to);
                if (!Double.isFinite(downstreamHeight) ||
                    !(downstreamHeight < terrain[index]))
                    throw new IllegalArgumentException(
                        "External river exit is not strictly downhill");
            }

            // A priority-flood path through a submerged depression is NOT an
            // instantaneous river. Such water needs a lake-volume transition.
            for (int i = 0; i < terrain.length; i++)
                if (surface[i] - terrain[i] >
                    1e-9 * Math.max(1.0, Math.abs(terrain[i])))
                    throw new IllegalStateException(
                        "Ponded lake requires a separate storage-aware activation");

            var runoff = new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
            for (var source : prepared.preview().season().runoff())
                if (runoff.putIfAbsent(source.address(), source.localWater()) != null)
                    throw new IllegalArgumentException("Duplicate seasonal water owner");

            var nodes = new ArrayList<HydrologyFlowGraphV2.Node>(addresses.size());
            for (int i = 0; i < addresses.size(); i++) {
                var address = addresses.get(i);
                var water = runoff.remove(address);
                if (water == null)
                    throw new IllegalArgumentException("Missing seasonal water owner");
                int next = spill[i];
                boolean terminal = next < 0;
                if (terminal != outlets.contains(i))
                    throw new IllegalArgumentException("Unverified drainage termination");
                var external = exits.get(i);
                nodes.add(new HydrologyFlowGraphV2.Node(address, water,
                    terminal ? external : addresses.get(next),
                    terminal && external == null));
            }
            if (!runoff.isEmpty())
                throw new IllegalArgumentException("Unknown seasonal water owner");
            var solved = HydrologyFlowGraphV2.solve(nodes);
            var budget = HydrologyConservationV2.verify(nodes, solved);
            if (!close(budget.input(), erosion.runoffVolume()) ||
                !close(budget.outlet() + budget.frontier(),
                    erosion.terminalVolume()))
                throw new IllegalArgumentException("Seasonal routing budget mismatch");

            if (runner.epoch() != oldEpoch ||
                !runner.worldState().equals(oldWorld) ||
                !runner.inboxSnapshot().equals(oldInbox))
                throw new IOException("World changed during seasonal preparation");
            // The runner atomically persists both stores and the epoch/inbox.
            // No pending water can be consumed on this restricted path.
            var committed = runner.advanceAndQueueKnownFrontier(
                HydrologySeasonalLandscapeStateV2.encode(prepared.state()),
                List.of(nodes));
            return new Committed(prepared, committed.committed().flow(),
                budget, committed.queuedTransfers());
        }
    }

    private static boolean close(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b) &&
            Math.abs(a - b) <= Math.max(1e-8, Math.max(a, b) * 1e-10);
    }
}
