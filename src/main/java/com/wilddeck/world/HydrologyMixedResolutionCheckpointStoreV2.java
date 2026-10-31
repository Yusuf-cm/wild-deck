package com.wilddeck.world;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.StandardOpenOption.*;

/**
 * Single-writer local atomic checkpoint for mixed-resolution hydrology.
 * Receipts, their delivery markers and the caller's world state share one
 * checksummed, atomically replaced file. This is NOT distributed consensus,
 * a multi-process CAS, or a cross-region two-phase commit.
 */
public final class HydrologyMixedResolutionCheckpointStoreV2 {
    private HydrologyMixedResolutionCheckpointStoreV2() {}
    private static final int MAGIC = 0x4d524332; // MRC2
    private static final int VERSION = 1;
    private static final int MAX_FIELD = 16 * 1024 * 1024;
    private static final int MAX_FILE = 2 * MAX_FIELD + 128;

    public record Checkpoint(long epoch, String worldState,
            HydrologyMixedResolutionEpochV2.Snapshot inbox) {
        public Checkpoint {
            if (epoch < 0) throw new IllegalArgumentException("Invalid checkpoint epoch");
            Objects.requireNonNull(worldState);
            Objects.requireNonNull(inbox);
            if (inbox.advancedThrough() != epoch)
                throw new IllegalArgumentException("World and receipt epochs differ");
        }
    }
    public record Committed(HydrologyMixedResolutionDischargeV2.Result flow,
                            Checkpoint checkpoint) {
        public Committed {
            Objects.requireNonNull(flow);
            Objects.requireNonNull(checkpoint);
        }
    }

    /** Create a new authoritative local checkpoint, never overwrite one. */
    public static void initialize(Path file, Checkpoint initial) throws IOException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(initial);
        if (Files.exists(file)) throw new IOException("Checkpoint already exists");
        write(file, initial);
    }

    /** Persist receipt acceptance BEFORE acknowledging its delivery. */
    public static Checkpoint receive(Path file, Checkpoint expected,
            HydrologyMixedResolutionEpochV2.Receipt receipt, double volume) throws IOException {
        Objects.requireNonNull(expected);
        var next = new Checkpoint(expected.epoch(), expected.worldState(),
            HydrologyMixedResolutionEpochV2.receive(expected.inbox(), receipt, volume));
        saveExpected(file, expected, next);
        return next;
    }

    /** Stage and solve first; persist world and delivered receipts atomically. */
    public static Committed advance(Path file, Checkpoint expected, String nextWorld,
            Collection<HydrologyMixedResolutionDischargeV2.Node> nodes,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) throws IOException {
        Objects.requireNonNull(expected);
        long epoch = Math.addExact(expected.epoch(), 1);
        var staged = HydrologyMixedResolutionEpochV2.prepare(
            expected.inbox(), epoch, nodes, verifiedMouths);
        var next = new Checkpoint(epoch, nextWorld, staged.next());
        saveExpected(file, expected, next);
        return new Committed(staged.flow(), next);
    }

    /**
     * Local optimistic freshness guard. An independent process could still
     * write between the load and rename; use one writer per checkpoint.
     */
    private static void saveExpected(Path file, Checkpoint expected, Checkpoint next)
            throws IOException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(next);
        if (!load(file).equals(expected))
            throw new IOException("Stale mixed-resolution checkpoint");
        if (next.epoch() < expected.epoch() || next.epoch() > expected.epoch() + 1)
            throw new IOException("Invalid checkpoint epoch transition");
        if (!next.inbox().receipts().entrySet().containsAll(
                expected.inbox().receipts().entrySet()) ||
            !next.inbox().delivered().containsAll(expected.inbox().delivered()))
            throw new IOException("Cannot erase accepted or delivered water");
        if (next.epoch() == expected.epoch() &&
            (!next.worldState().equals(expected.worldState()) ||
             !next.inbox().delivered().equals(expected.inbox().delivered())))
            throw new IOException("Same-epoch receipt update cannot change world or consumption");
        if (next.equals(expected)) return;
        write(file, next);
    }

    public static Checkpoint load(Path file) throws IOException {
        Objects.requireNonNull(file);
        if (Files.size(file) > MAX_FILE) throw new IOException("Checkpoint too large");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 4 + 4 + 8 + 4 + 4 + 32)
            throw new IOException("Truncated checkpoint");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        byte[] checksum = Arrays.copyOfRange(bytes, body.length, bytes.length);
        if (!MessageDigest.isEqual(checksum, sha256(body)))
            throw new IOException("Checkpoint checksum mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
                throw new IOException("Unknown checkpoint format");
            long epoch = in.readLong();
            int worldSize = in.readInt();
            if (worldSize < 0 || worldSize > MAX_FIELD || worldSize > in.available())
                throw new IOException("Invalid world length");
            String world = new String(in.readNBytes(worldSize), StandardCharsets.UTF_8);
            int inboxSize = in.readInt();
            if (inboxSize < 0 || inboxSize > MAX_FIELD || inboxSize != in.available())
                throw new IOException("Invalid receipt length");
            String inbox = new String(in.readNBytes(inboxSize), StandardCharsets.US_ASCII);
            return new Checkpoint(epoch, world, HydrologyMixedResolutionEpochV2.decode(inbox));
        } catch (IllegalArgumentException | EOFException ex) {
            throw new IOException("Invalid checkpoint state", ex);
        }
    }

    private static void write(Path file, Checkpoint checkpoint) throws IOException {
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Checkpoint directory missing");
        byte[] world = checkpoint.worldState().getBytes(StandardCharsets.UTF_8);
        byte[] inbox = HydrologyMixedResolutionEpochV2.encode(
            checkpoint.inbox()).getBytes(StandardCharsets.US_ASCII);
        if (world.length > MAX_FIELD || inbox.length > MAX_FIELD)
            throw new IOException("Checkpoint field too large");
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(checkpoint.epoch());
            out.writeInt(world.length);
            out.write(world);
            out.writeInt(inbox.length);
            out.write(inbox);
        }
        byte[] body = buffer.toByteArray();
        var encoded = new ByteArrayOutputStream(body.length + 32);
        encoded.write(body);
        encoded.write(sha256(body));
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".pending");
        try {
            try (var channel = FileChannel.open(temp, WRITE, TRUNCATE_EXISTING)) {
                var data = ByteBuffer.wrap(encoded.toByteArray());
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING);
            try (var dir = FileChannel.open(parent, READ)) { dir.force(true); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
