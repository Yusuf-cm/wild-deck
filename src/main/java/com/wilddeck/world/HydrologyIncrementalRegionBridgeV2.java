package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only bridge from a settled incremental priority flood to the existing
 * durable region/receipt scheduler. Each canonical spherical sample belongs
 * to exactly one region (west/north at shared vertices, x=0 at both poles).
 *
 * A partial flood publishes only settled routes. This bridge never invents
 * downstream paths for unsettled samples or imports their rainfall. Callers
 * must supply exactly one explicit water volume, including zero, per settled
 * sample for the epoch. The world runner is responsible for atomic receipt
 * consumption; the flood checkpoint and world checkpoint are NOT one
 * distributed transaction.
 */
public final class HydrologyIncrementalRegionBridgeV2 {
    private HydrologyIncrementalRegionBridgeV2() {}

    public static HydrologyRegionSchedulerV2.Region owner(
            GlobalHydrologyAddressV2.Sample address) {
        Objects.requireNonNull(address);
        if (!HydrologySphericalTopologyV2.isCanonical(address))
            throw new IllegalArgumentException("Noncanonical spherical ownership");
        long column = address.x() == 0 ? 0 :
            (address.x() - 1) / address.divisions();
        long row = address.y() == 0 ? 0 :
            (address.y() - 1) / address.divisions();
        return new HydrologyRegionSchedulerV2.Region(column, row);
    }

    /**
     * Validate settled downstream ancestry before assigning any rainfall.
     * A forged partial snapshot must not create false terminal catchments.
     */
    private static void verify(HydrologyIncrementalPriorityFloodV2.Snapshot snapshot) {
        if (snapshot.settled().isEmpty())
            throw new IllegalArgumentException("Empty settled drainage");
        GlobalHydrologyAddressV2.Sample first = snapshot.settled().keySet().iterator().next();
        for (var entry : snapshot.settled().entrySet()) {
            var address = entry.getKey();
            var route = entry.getValue();
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                address.level() != first.level() ||
                address.divisions() != first.divisions())
                throw new IllegalArgumentException("Invalid settled spherical sample");
            if (route.downstream() == null) {
                if (route.steps() != 0 || !route.catchment().outlet().equals(address))
                    throw new IllegalArgumentException("Unverified terminal catchment");
            } else {
                var parent = snapshot.settled().get(route.downstream());
                if (parent == null ||
                    !HydrologySphericalTopologyV2.adjacent(address, route.downstream()) ||
                    route.steps() != parent.steps() + 1 ||
                    Double.compare(route.spillElevation(), parent.spillElevation()) < 0 ||
                    !route.catchment().equals(parent.catchment()))
                    throw new IllegalArgumentException("Invalid settled drainage ancestry");
            }
        }
    }

    /**
     * Partition only authoritative settled routes. Missing or duplicate water
     * owners fail closed rather than silently treating unknown rainfall as zero.
     * The returned lists can be supplied to advanceAndQueueKnownFrontier.
     */
    public static Map<HydrologyRegionSchedulerV2.Region, List<HydrologyFlowGraphV2.Node>>
            partition(HydrologyIncrementalPriorityFloodV2.Snapshot snapshot,
                      Collection<HydrologyGlobalSlopeRouterV2.Source> ownedWater) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(ownedWater);
        verify(snapshot);
        var water = new TreeMap<GlobalHydrologyAddressV2.Sample, Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var source : ownedWater) {
            Objects.requireNonNull(source);
            if (!HydrologySphericalTopologyV2.isCanonical(source.address()) ||
                !snapshot.settled().containsKey(source.address()) ||
                water.putIfAbsent(source.address(), source.localWater()) != null)
                throw new IllegalArgumentException("Unknown or duplicate owned water");
        }
        if (!water.keySet().equals(snapshot.settled().keySet()))
            throw new IllegalArgumentException("Missing settled water owner");
        var grouped = new TreeMap<HydrologyRegionSchedulerV2.Region,
                                  List<HydrologyFlowGraphV2.Node>>();
        for (var entry : snapshot.settled().entrySet()) {
            var route = entry.getValue();
            var node = new HydrologyFlowGraphV2.Node(
                entry.getKey(), water.get(entry.getKey()),
                route.downstream(), route.downstream() == null);
            grouped.computeIfAbsent(owner(entry.getKey()),
                ignored -> new ArrayList<>()).add(node);
        }
        var immutable = new TreeMap<HydrologyRegionSchedulerV2.Region,
                                    List<HydrologyFlowGraphV2.Node>>();
        grouped.forEach((region, nodes) -> immutable.put(region, List.copyOf(nodes)));
        return Collections.unmodifiableMap(immutable);
    }

    /**
     * Refuse to publish a session different from the durable replay checkpoint.
     * This is a read-only preflight, NOT a cross-file atomic world activation.
     */
    public static Map<HydrologyRegionSchedulerV2.Region, List<HydrologyFlowGraphV2.Node>>
            partitionPersisted(Path floodCheckpoint, String terrainRevision,
                               HydrologyIncrementalPriorityFloodV2.Session session,
                               Collection<HydrologyGlobalSlopeRouterV2.Source> ownedWater)
            throws IOException {
        Objects.requireNonNull(session);
        var persisted = HydrologyIncrementalFloodCheckpointStoreV2.load(floodCheckpoint);
        if (!persisted.equals(session.checkpoint(terrainRevision)))
            throw new IllegalArgumentException("Flood session differs from durable checkpoint");
        var regions = partition(session.snapshot(), ownedWater);
        // Rainfall enumeration can be slow or caller-backed. A newer flood
        // checkpoint must not be mistaken for the session we partitioned.
        // This is a local optimistic preflight, not a cross-file CAS.
        if (!HydrologyIncrementalFloodCheckpointStoreV2.load(floodCheckpoint)
                .equals(persisted))
            throw new IOException("Flood checkpoint changed during region partition");
        return regions;
    }

    /**
     * Bounded, restartable discovery for destinations with undelivered durable
     * receipts. This reads the authoritative world inbox, not caller-supplied
     * water, and advances the independently checkpointed flood at most once.
     *
     * The result explicitly reports destinations still unsettled. Only a
     * ready result may be used to plan their routing; no world epoch or receipt
     * is consumed here. The flood and world files are NOT one transaction.
     */
    public record PendingDiscovery(
            HydrologyIncrementalPriorityFloodV2.Snapshot snapshot,
            Set<GlobalHydrologyAddressV2.Sample> unresolved) {
        public PendingDiscovery {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(unresolved);
            var sorted = new TreeSet<GlobalHydrologyAddressV2.Sample>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            sorted.addAll(unresolved);
            unresolved = Collections.unmodifiableSet(sorted);
        }
        public boolean ready() { return unresolved.isEmpty(); }
    }

    public static PendingDiscovery discoverPendingPersisted(
            Path floodCheckpoint, Path worldCheckpoint, String terrainRevision,
            HydrologyIncrementalPriorityFloodV2.Session session,
            int maxSettlements) throws IOException {
        Objects.requireNonNull(floodCheckpoint);
        Objects.requireNonNull(worldCheckpoint);
        Objects.requireNonNull(session);
        if (maxSettlements < 1 ||
            maxSettlements > HydrologyIncrementalPriorityFloodV2.MAX_SAMPLES)
            throw new IllegalArgumentException("Invalid pending discovery budget");

        var expected = HydrologyIncrementalFloodCheckpointStoreV2.load(floodCheckpoint);
        if (!expected.equals(session.checkpoint(terrainRevision)))
            throw new IllegalArgumentException("Stale or incompatible flood session");
        var world = HydrologyCheckpointStoreV2.load(worldCheckpoint);
        var inbox = HydrologyInboxCodecV2.decode(world.inboxState());
        var first = expected.verifiedOutlets().iterator().next();
        var targets = new TreeSet<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var key : inbox.receipts().keySet()) {
            if (inbox.delivered().contains(key)) continue;
            var destination = key.to();
            if (!HydrologySphericalTopologyV2.isCanonical(destination) ||
                !HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                destination.level() != first.level() ||
                destination.divisions() != first.divisions())
                throw new IllegalArgumentException(
                    "Pending receipt endpoints do not match canonical flood resolution");
            targets.add(destination);
        }
        var snapshot = session.snapshot();
        if (!snapshot.settled().keySet().containsAll(targets)) {
            snapshot = session.advance(maxSettlements);
            // A concurrently changed world inbox must not be mistaken for the
            // set of destinations that was actually inspected.
            if (!HydrologyCheckpointStoreV2.load(worldCheckpoint).equals(world))
                throw new IOException("World inbox changed during flood discovery");
            var next = session.checkpoint(terrainRevision);
            if (next.settledCount() > expected.settledCount())
                HydrologyIncrementalFloodCheckpointStoreV2.saveNext(
                    floodCheckpoint, expected, next);
        }
        var unresolved = new TreeSet<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        unresolved.addAll(targets);
        unresolved.removeAll(snapshot.settled().keySet());
        if (!HydrologyCheckpointStoreV2.load(worldCheckpoint).equals(world))
            throw new IOException("World inbox changed during flood discovery");
        return new PendingDiscovery(snapshot, unresolved);
    }

    /**
     * Fail closed when a scheduler asks for a region not yet settled by the
     * flood. An empty provider response could otherwise hide missing routes.
     */
    public static HydrologyRegionSchedulerV2.Provider provider(
            Map<HydrologyRegionSchedulerV2.Region, List<HydrologyFlowGraphV2.Node>> partition) {
        Objects.requireNonNull(partition);
        var copy = new TreeMap<HydrologyRegionSchedulerV2.Region,
                               List<HydrologyFlowGraphV2.Node>>();
        partition.forEach((region, nodes) ->
            copy.put(Objects.requireNonNull(region), List.copyOf(nodes)));
        return region -> {
            var nodes = copy.get(region);
            if (nodes == null)
                throw new IllegalArgumentException("Requested region has no settled drainage");
            return nodes;
        };
    }
}
