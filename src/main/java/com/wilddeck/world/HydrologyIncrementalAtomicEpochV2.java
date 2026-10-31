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
            Collection<HydrologyGlobalSlopeRouterV2.Source> ownedWater)
            throws IOException {
        Objects.requireNonNull(runner);
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(session);
        Objects.requireNonNull(ownedWater);
        var previous = decode(runner.worldState()).flood();
        var current = session.checkpoint(terrainRevision);
        if (!previous.terrainRevision().equals(current.terrainRevision()) ||
            !previous.verifiedOutlets().equals(current.verifiedOutlets()) ||
            current.settledCount() < previous.settledCount() ||
            (current.settledCount() == previous.settledCount() &&
             !current.equals(previous)))
            throw new IllegalArgumentException("Incompatible or regressed flood checkpoint");

        var snapshot = session.snapshot();
        var first = current.verifiedOutlets().iterator().next();
        long due = Math.addExact(runner.epoch(), 1);
        for (var entry : runner.inboxSnapshot().receipts().entrySet()) {
            var key = entry.getKey();
            if (runner.inboxSnapshot().delivered().contains(key) || key.epoch() > due)
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
        if (!current.equals(session.checkpoint(terrainRevision)))
            throw new IllegalArgumentException("Flood changed during water partition");
        return runner.advanceAndQueueKnownFrontier(
            encode(new Bound(nextWorld, current)), partition.values());
    }
}
