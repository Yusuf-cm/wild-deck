package com.wilddeck.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in, single-file handoff of settled incremental drainage to the durable
 * water scheduler. The flood replay identity, caller's opaque world payload,
 * and receipt inbox are committed in ONE world checkpoint replacement.
 *
 * Unlike the independent flood checkpoint file, the embedded identity cannot
 * be committed separately from receipt consumption. The caller still owns the
 * immutable terrain oracle, the verified outlets, and all local water sources.
 * This is not an interprocess CAS, a distributed transaction, or a complete
 * global catchment/water inventory.
 */
public final class HydrologyIncrementalAtomicEpochV2 {
    private HydrologyIncrementalAtomicEpochV2() {}
    private static final String PREFIX = "WDI2:";
    private static final int VERSION = 1;
    private static final int MAX_WORLD_BYTES = 16 * 1024 * 1024;

    public record Bound(String world,
            HydrologyIncrementalPriorityFloodV2.Checkpoint flood) {
        public Bound {
            Objects.requireNonNull(world);
            Objects.requireNonNull(flood);
        }
    }

    /** Embed the flood replay checkpoint in the same opaque world payload. */
    public static String encode(Bound bound) {
        Objects.requireNonNull(bound);
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION);
                var world = bound.world().getBytes(StandardCharsets.UTF_8);
                if (world.length > MAX_WORLD_BYTES)
                    throw new IllegalArgumentException("World payload too large");
                out.writeInt(world.length);
                out.write(world);
                var flood = bound.flood();
                out.writeUTF(flood.terrainRevision());
                out.writeInt(flood.settledCount());
                out.writeUTF(flood.stateSha256());
                out.writeInt(flood.verifiedOutlets().size());
                for (var address : flood.verifiedOutlets()) {
                    out.writeInt(address.level());
                    out.writeInt(address.divisions());
                    out.writeLong(address.x());
                    out.writeLong(address.y());
                }
            }
            return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot encode incremental flood binding", ex);
        }
    }

    public static Bound decode(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX))
            throw new IllegalArgumentException("World is not bound to incremental drainage");
        try {
            var data = Base64.getDecoder().decode(encoded.substring(PREFIX.length()));
            if (data.length > MAX_WORLD_BYTES + 128 * 1024)
                throw new IllegalArgumentException("Bound world payload too large");
            try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
                if (in.readInt() != VERSION)
                    throw new IllegalArgumentException("Unsupported flood binding version");
                int length = in.readInt();
                if (length < 0 || length > MAX_WORLD_BYTES || length > in.available())
                    throw new IllegalArgumentException("Invalid bound world length");
                var worldBytes = in.readNBytes(length);
                var world = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(worldBytes)).toString();
                String revision = in.readUTF();
                int settled = in.readInt();
                String digest = in.readUTF();
                int count = in.readInt();
                if (count < 1 || count > HydrologyIncrementalPriorityFloodV2.MAX_SAMPLES)
                    throw new IllegalArgumentException("Invalid outlet count");
                var outlets = new TreeSet<GlobalHydrologyAddressV2.Sample>(
                    HydrologyFlowGraphV2.ADDRESS_ORDER);
                for (int i = 0; i < count; i++) {
                    var address = new GlobalHydrologyAddressV2.Sample(
                        in.readInt(), in.readInt(), in.readLong(), in.readLong());
                    if (!outlets.add(address))
                        throw new IllegalArgumentException("Duplicate verified outlet");
                }
                if (in.available() != 0)
                    throw new IllegalArgumentException("Trailing flood binding data");
                return new Bound(world, new HydrologyIncrementalPriorityFloodV2.Checkpoint(
                    revision, outlets, settled, digest));
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid incremental drainage binding", ex);
        }
    }

    /** Reconstruct authoritative drainage directly from the atomic world file. */
    public static HydrologyIncrementalPriorityFloodV2.Session resumeBound(
            java.nio.file.Path worldCheckpoint, String terrainRevision,
            HydrologyGlobalSlopeRouterV2.Elevations terrain) throws IOException {
        var bound = decode(HydrologyCheckpointStoreV2.load(worldCheckpoint).worldState());
        return HydrologyIncrementalPriorityFloodV2.resume(
            bound.flood(), terrainRevision, terrain);
    }

    /**
     * Discover due, undelivered water destinations using the authoritative
     * bound world checkpoint. A bounded batch is persisted IN THE SAME FILE
     * without changing epoch, inbox, or the caller's opaque world payload.
     *
     * Unsettled water stays parked. A restart resumes from the embedded flood
     * identity, not from an independent flood file. Caller terrain must be an
     * immutable oracle matching terrainRevision. This is single-writer local
     * persistence, not a distributed compare-and-swap.
     */
    public static HydrologyIncrementalRegionBridgeV2.PendingDiscovery discoverPending(
            HydrologyWorldRunnerV2 runner, String terrainRevision,
            HydrologyGlobalSlopeRouterV2.Elevations terrain,
            int maxSettlements) throws IOException {
        synchronized (Objects.requireNonNull(runner)) {
            Objects.requireNonNull(terrainRevision);
            Objects.requireNonNull(terrain);
            if (maxSettlements < 1 ||
                maxSettlements > HydrologyIncrementalPriorityFloodV2.MAX_SAMPLES)
                throw new IllegalArgumentException("Invalid discovery batch budget");
            var boundState = runner.worldState();
            var inboxBefore = runner.inboxSnapshot();
            long epochBefore = runner.epoch();
            var bound = decode(boundState);
            var previous = bound.flood();
            var session = HydrologyIncrementalPriorityFloodV2.resume(
                previous, terrainRevision, terrain);
            var first = previous.verifiedOutlets().iterator().next();
            var targets = new TreeSet<GlobalHydrologyAddressV2.Sample>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            long due = Math.addExact(runner.epoch(), 1);
            for (var key : runner.inboxSnapshot().receipts().keySet()) {
                if (runner.inboxSnapshot().delivered().contains(key) ||
                    key.epoch() > due) continue;
                if (!HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                    !HydrologySphericalTopologyV2.isCanonical(key.to()) ||
                    key.from().level() != first.level() ||
                    key.from().divisions() != first.divisions() ||
                    key.from().level() != first.level() ||
                key.from().divisions() != first.divisions() ||
                key.to().level() != first.level() ||
                    key.to().divisions() != first.divisions())
                    throw new IllegalArgumentException(
                        "Due receipt endpoints do not match canonical flood resolution");
                targets.add(key.to());
            }
            var snapshot = session.snapshot();
            String expectedState = boundState;
            if (!snapshot.settled().keySet().containsAll(targets)) {
                snapshot = session.advance(maxSettlements);
                var updated = session.checkpoint(terrainRevision);
                if (runner.epoch() != epochBefore ||
                    !runner.inboxSnapshot().equals(inboxBefore) ||
                    !runner.worldState().equals(boundState))
                    throw new IOException("World inbox changed during pending-water discovery");
                if (updated.settledCount() > previous.settledCount()) {
                    expectedState = encode(new Bound(bound.world(), updated));
                    runner.checkpointIncrementalFloodProgress(expectedState);
                }
            }
            if (runner.epoch() != epochBefore ||
                !runner.inboxSnapshot().equals(inboxBefore) ||
                !runner.worldState().equals(expectedState))
                throw new IOException("World inbox changed during pending-water discovery");
            targets.removeAll(snapshot.settled().keySet());
            return new HydrologyIncrementalRegionBridgeV2.PendingDiscovery(
                snapshot, targets);
        }
    }

    /** Result of automatic discovery followed by optional epoch activation. */
    public record DiscoveryAdvance(
            HydrologyIncrementalRegionBridgeV2.PendingDiscovery discovery,
            HydrologyDurableEpochV2.Routed committed) {
        public DiscoveryAdvance {
            Objects.requireNonNull(discovery);
            if (!discovery.ready() && committed != null)
                throw new IllegalArgumentException("Unsettled water cannot be committed");
        }
        public boolean advanced() { return committed != null; }
    }

    /**
     * Automatically activate ONLY when all due parked water has settled
     * drainage. The water provider is called only on the ready path and must
     * return one unique local-water owner for every settled sample.
     */
    public static DiscoveryAdvance discoverAndAdvance(
            HydrologyWorldRunnerV2 runner, String nextWorld,
            String terrainRevision, HydrologyGlobalSlopeRouterV2.Elevations terrain,
            int maxSettlements,
            java.util.function.Function<HydrologyIncrementalPriorityFloodV2.Snapshot,
                Collection<HydrologyGlobalSlopeRouterV2.Source>> ownedWater)
            throws IOException {
        synchronized (Objects.requireNonNull(runner)) {
            Objects.requireNonNull(nextWorld);
            Objects.requireNonNull(ownedWater);
            var discovery = discoverPending(runner, terrainRevision, terrain, maxSettlements);
            if (!discovery.ready()) return new DiscoveryAdvance(discovery, null);
            var session = HydrologyIncrementalPriorityFloodV2.resume(
                decode(runner.worldState()).flood(), terrainRevision, terrain);
            var water = Objects.requireNonNull(ownedWater.apply(discovery.snapshot()));
            var committed = advance(runner, nextWorld, terrainRevision,
                session, terrain, water);
            return new DiscoveryAdvance(discovery, committed);
        }
    }

    /**
     * Commit one water epoch using ONLY settled, globally owned drainage.
     * All due parked destinations must be settled. Future-epoch receipts may
     * remain parked. A failed solve/receipt check leaves the world file intact.
     *
     * The session is caller-provided; its terrain revision and progress are
     * checked against the last embedded identity, but the terrain itself is
     * not independently persisted or authenticated here.
     */
    public static HydrologyDurableEpochV2.Routed advance(
            HydrologyWorldRunnerV2 runner, String nextWorld,
            String terrainRevision,
            HydrologyIncrementalPriorityFloodV2.Session session,
            HydrologyGlobalSlopeRouterV2.Elevations terrain,
            Collection<HydrologyGlobalSlopeRouterV2.Source> ownedWater)
            throws IOException {
        // Serialize the preflight and commit with this runner's synchronized
        // receipt writes. External processes still need separate coordination.
        synchronized (Objects.requireNonNull(runner)) {
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(session);
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(ownedWater);
        var previous = decode(runner.worldState()).flood();
        var current = session.checkpoint(terrainRevision);
        if (!previous.terrainRevision().equals(current.terrainRevision()) ||
            !previous.verifiedOutlets().equals(current.verifiedOutlets()) ||
            current.settledCount() < previous.settledCount() ||
            (current.settledCount() == previous.settledCount() &&
             !current.equals(previous)))
            throw new IllegalArgumentException("Incompatible or regressed flood checkpoint");
        // Independently replay the previous committed state and the proposed
        // forward progress against the caller-supplied immutable terrain.
        // A forged session with the same outlet/count cannot change routing.
        var replay = HydrologyIncrementalPriorityFloodV2.resume(
            previous, terrainRevision, terrain);
        int delta = current.settledCount() - previous.settledCount();
        if (delta > 0) replay.advance(delta);
        if (!replay.checkpoint(terrainRevision).equals(current))
            throw new IllegalArgumentException("Flood continuation failed deterministic replay");

        var snapshot = session.snapshot();
        var first = current.verifiedOutlets().iterator().next();
        long due = Math.addExact(runner.epoch(), 1);
        var inboxBefore = runner.inboxSnapshot();
        for (var entry : inboxBefore.receipts().entrySet()) {
            var key = entry.getKey();
            if (inboxBefore.delivered().contains(key) || key.epoch() > due)
                continue;
            if (!HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                !HydrologySphericalTopologyV2.isCanonical(key.to()) ||
                key.to().level() != first.level() ||
                key.to().divisions() != first.divisions() ||
                !snapshot.settled().containsKey(key.to()))
                throw new IllegalArgumentException(
                    "Due receipt has no settled canonical drainage destination");
        }

        var partition = HydrologyIncrementalRegionBridgeV2.partition(
            snapshot, ownedWater);
        if (runner.epoch() != due - 1 ||
            !runner.inboxSnapshot().equals(inboxBefore))
            throw new IOException("World inbox changed during flood partition");
        if (!current.equals(session.checkpoint(terrainRevision)))
            throw new IllegalArgumentException("Flood changed during water partition");
        return runner.advanceAndQueueKnownFrontier(
            encode(new Bound(nextWorld, current)), partition.values());
        }
    }
}
