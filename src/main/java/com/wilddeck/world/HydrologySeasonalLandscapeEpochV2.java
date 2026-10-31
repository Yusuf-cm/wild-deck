package com.wilddeck.world;

import java.io.IOException;
import java.util.*;

/**
 * Opt-in atomic seasonal transition for a CLOSED, lake-free terrain window.
 *
 * Recomputes snow/soil runoff, incision, sediment export and post-erosion
 * drainage from the last committed landscape. Only verified outlets may
 * terminate flow. Rejects ponded depressions and all undelivered receipts:
 * neither lake storage nor arbitrary old-topology boundary water can be safely
 * migrated by this restricted transition. A separate opt-in terminal-receipt
 * path accepts only verified downhill landings at physical outlets.
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
        return advance(runner, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, externalExits, false, false, false);
    }

    /**
     * Receive old-epoch boundary water only where it lands directly on a
     * verified terminal outlet. The source must be outside this window, the
     * recorded edge canonical and downhill both before and after erosion,
     * and every undelivered receipt must be due by the next epoch.
     *
     * This is intentionally NOT arbitrary old-topology migration: incoming
     * water cannot affect in-window incision or pass through a ponded lake.
     * Outlet receipts, seasonal terrain and outgoing transfers are checkpointed
     * atomically by the existing single-writer runner.
     */
    public static Committed advanceWithVerifiedOutletReceipts(
            HydrologyWorldRunnerV2 runner,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets,
            Map<Integer,GlobalHydrologyAddressV2.Sample> externalExits)
            throws IOException {
        return advance(runner, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, externalExits, true, false, false);
    }

    /**
     * Opt-in interior boundary-water reconciliation. Imported water drives
     * incision and sediment BEFORE the seasonal landscape is prepared.
     * Each receipt must enter from a canonical external neighbor, be downhill
     * before and after erosion, and retain its complete downstream path to a
     * verified outlet. Any route change or ponded lake rejects the transition
     * without consuming the checkpointed receipts.
     */
    public static Committed advanceWithVerifiedInteriorReceipts(
            HydrologyWorldRunnerV2 runner,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets,
            Map<Integer,GlobalHydrologyAddressV2.Sample> externalExits)
            throws IOException {
        return advance(runner, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, externalExits, true, true, false);
    }

    /**
     * Opt-in reconciliation of already-landed interior water across a changed
     * in-window river path. The geographic receipt landing stays fixed; both
     * old and new routes must reach explicitly verified outlets without
     * ponding or cycles. Imported water participates in incision before the
     * rebuilt drainage is solved, and is consumed only with the atomic
     * seasonal checkpoint. A changed terminal is reported by the independent
     * HydrologyRouteMigrationV2 audit, not treated as a globally stable basin.
     *
     * This does not authenticate external historical drainage, support
     * mixed-resolution routing, or migrate future-dated receipts.
     */
    public static Committed advanceWithReconciledInteriorReceipts(
            HydrologyWorldRunnerV2 runner,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets,
            Map<Integer,GlobalHydrologyAddressV2.Sample> externalExits)
            throws IOException {
        return advance(runner, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, externalExits, true, true, true);
    }

    private static Committed advance(
            HydrologyWorldRunnerV2 runner,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets,
            Map<Integer,GlobalHydrologyAddressV2.Sample> externalExits,
            boolean acceptVerifiedOutletReceipts,
            boolean reconcileInterior,
            boolean allowReroute)
            throws IOException {
        synchronized (Objects.requireNonNull(runner)) {
            String oldWorld = runner.worldState();
            long oldEpoch = runner.epoch();
            var oldInbox = runner.inboxSnapshot();
            // Even future-dated receipts must remain bound to their original
            // topology. This restricted mode has no topology migration.
            if (!acceptVerifiedOutletReceipts) {
                for (var key : oldInbox.receipts().keySet())
                    if (!oldInbox.delivered().contains(key))
                        throw new IllegalStateException(
                            "Seasonal terrain activation requires no pending boundary water");
            }

            var previous = HydrologySeasonalLandscapeStateV2.decode(oldWorld);
            Map<GlobalHydrologyAddressV2.Sample,Double> erosionIncoming = Map.of();
            if (reconcileInterior) {
                var ownedBefore = Set.copyOf(addresses);
                if (ownedBefore.size() != addresses.size())
                    throw new IllegalArgumentException("Duplicate terrain owner");
                var before = HydrologyTerrainOverlayV2.elevations(
                    seed, previous.terrain());
                long due = Math.addExact(oldEpoch, 1);
                for (var receipt : oldInbox.receipts().entrySet()) {
                    var key = receipt.getKey();
                    if (oldInbox.delivered().contains(key)) continue;
                    if (key.epoch() > due)
                        throw new IllegalStateException(
                            "Future old-topology receipt needs separate reconciliation");
                    if (!ownedBefore.contains(key.to()) ||
                        ownedBefore.contains(key.from()) ||
                        !HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                        !HydrologySphericalTopologyV2.isCanonical(key.to()) ||
                        !HydrologySphericalTopologyV2.adjacent(key.from(), key.to()))
                        throw new IllegalArgumentException(
                            "Invalid interior boundary receipt edge");
                    double from = before.at(key.from());
                    double to = before.at(key.to());
                    if (!Double.isFinite(from) || !Double.isFinite(to) ||
                        !(from > to))
                        throw new IllegalArgumentException(
                            "Interior receipt was not downhill on committed terrain");
                }
                erosionIncoming = HydrologyEpochInboxV2.restore(oldInbox)
                    .claimAvailable(due, ownedBefore);
            }
            var prepared = reconcileInterior
                ? HydrologySeasonalLandscapeStateV2.prepareWithIncoming(
                    previous, addresses, width, height, seed, weather,
                    radiusMeters, sedimentDensity, erosionStrength, maximumCut,
                    transportCapacity, verifiedOutlets, erosionIncoming)
                : HydrologySeasonalLandscapeStateV2.prepare(
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

            // A previously dispatched receipt has a fixed physical landing.
            // The terminal-only mode excludes imported water from incision;
            // the interior mode includes it before erosion and requires each
            // old downstream path to remain identical after terrain changes.
            Map<GlobalHydrologyAddressV2.Sample,Double> dueIncoming = Map.of();
            double incomingTotal = 0;
            if (acceptVerifiedOutletReceipts) {
                var indices = new HashMap<GlobalHydrologyAddressV2.Sample,Integer>();
                for (int i = 0; i < addresses.size(); i++)
                    if (indices.putIfAbsent(addresses.get(i), i) != null)
                        throw new IllegalArgumentException("Duplicate terrain owner");
                var before = HydrologyTerrainOverlayV2.elevations(
                    seed, previous.terrain());
                long dueEpoch = Math.addExact(oldEpoch, 1);
                for (var receipt : oldInbox.receipts().entrySet()) {
                    var key = receipt.getKey();
                    if (oldInbox.delivered().contains(key)) continue;
                    if (key.epoch() > dueEpoch)
                        throw new IllegalStateException(
                            "Future old-topology receipt needs separate reconciliation");
                    Integer landing = indices.get(key.to());
                    if (landing == null ||
                        (!outlets.contains(landing) && !reconcileInterior) ||
                        owned.contains(key.from()) ||
                        !HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                        !HydrologySphericalTopologyV2.isCanonical(key.to()) ||
                        !HydrologySphericalTopologyV2.adjacent(key.from(), key.to()))
                        throw new IllegalArgumentException(
                            "Boundary receipt does not land at a verified external outlet");
                    double oldFrom = before.at(key.from());
                    double oldTo = before.at(key.to());
                    double newFrom = elevation.at(key.from());
                    double newTo = terrain[landing];
                    if (!Double.isFinite(oldFrom) || !Double.isFinite(oldTo) ||
                        !Double.isFinite(newFrom) || !Double.isFinite(newTo) ||
                        !(oldFrom > oldTo) || !(newFrom > newTo))
                        throw new IllegalArgumentException(
                            "Boundary receipt is not downhill across both terrain epochs");
                    incomingTotal += receipt.getValue();
                    if (!Double.isFinite(incomingTotal))
                        throw new IllegalArgumentException("Incoming water overflow");
                }
                // The preview includes interior imported discharge in erosion;
                // the graph and durable runner must inject the same water once.
                if (reconcileInterior) {
                    var oldGrid = new double[addresses.size()];
                    for (int i = 0; i < oldGrid.length; i++)
                        oldGrid[i] = before.at(addresses.get(i));
                    var oldLakes = LakeSpillV2.fillToVerifiedOutlets(
                        width, height, oldGrid, outlets);
                    var oldRoute = oldLakes.spillTo();
                    var oldSurface = oldLakes.waterSurface();
                    for (int i = 0; i < oldGrid.length; i++)
                        if (oldSurface[i] - oldGrid[i] >
                            1e-9 * Math.max(1.0, Math.abs(oldGrid[i])))
                            throw new IllegalStateException(
                                "Old ponded lake requires storage-aware reconciliation");
                    if (allowReroute) {
                        var landings = new TreeSet<Integer>();
                        for (var landing : erosionIncoming.keySet())
                            landings.add(indices.get(landing));
                        HydrologyRouteMigrationV2.verify(
                            oldRoute, spill, outlets, landings);
                    } else {
                        for (var landing : erosionIncoming.keySet()) {
                            int cursor = indices.get(landing);
                            var visited = new HashSet<Integer>();
                            while (cursor >= 0) {
                                if (!visited.add(cursor) || oldRoute[cursor] != spill[cursor])
                                    throw new IllegalStateException(
                                        "Imported river changed downstream route during erosion");
                                cursor = oldRoute[cursor];
                            }
                        }
                    }
                    dueIncoming = erosionIncoming;
                } else {
                    // Claim only a STAGED inbox for preflight.
                    dueIncoming = HydrologyEpochInboxV2.restore(oldInbox)
                        .claimAvailable(dueEpoch, owned);
                }
            }

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
            var effective = HydrologyEpochSolverV2.prepare(
                List.of(nodes), dueIncoming);
            var solved = HydrologyFlowGraphV2.solve(effective);
            var budget = HydrologyConservationV2.verify(effective, solved);
            if (!close(budget.input(), erosion.runoffVolume() + incomingTotal) ||
                !close(budget.outlet() + budget.frontier(),
                    erosion.terminalVolume() + (reconcileInterior ? 0 : incomingTotal)))
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
