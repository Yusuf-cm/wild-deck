package com.wilddeck.world;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.*;

/**
 * Local single-writer, checksummed, atomically replaced replay checkpoint for
 * an incremental spherical priority flood. The checkpoint records a terrain
 * revision, verified outlet anchors, settlement count and a digest of both
 * settled routes and the discovered frontier. Restoring requires deterministic
 * replay against the same immutable terrain oracle.
 *
 * This is NOT a distributed CAS, a full serialized frontier queue, a live
 * hydrology receipt checkpoint, or independent proof of terrain provenance.
 */
public final class HydrologyIncrementalFloodCheckpointStoreV2 {
    private HydrologyIncrementalFloodCheckpointStoreV2() {}
    private static final int MAGIC = 0x57444632; // WDF2
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 128 * 1024;

    public static void save(Path destination,
            HydrologyIncrementalPriorityFloodV2.Checkpoint checkpoint)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(checkpoint);
        Path target = destination.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Checkpoint parent directory must exist");
        if (Files.exists(target)) {
            var previous = load(target);
            if (!previous.terrainRevision().equals(checkpoint.terrainRevision()) ||
                !previous.verifiedOutlets().equals(checkpoint.verifiedOutlets()))
                throw new IOException("Flood identity or terrain revision changed");
            if (checkpoint.settledCount() < previous.settledCount())
                throw new IOException("Flood settlement regression");
            if (checkpoint.settledCount() == previous.settledCount()) {
                if (previous.equals(checkpoint)) return;
                throw new IOException("Conflicting flood checkpoint");
            }
        }
        byte[] bytes = encode(checkpoint);
        Path temporary = Files.createTempFile(parent,
            target.getFileName().toString() + ".", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    WRITE, TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(parent, READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static HydrologyIncrementalPriorityFloodV2.Checkpoint load(
            Path destination) throws IOException {
        Objects.requireNonNull(destination);
        if (Files.size(destination) > MAX_BYTES)
            throw new IOException("Flood checkpoint too large");
        byte[] bytes = Files.readAllBytes(destination);
        if (bytes.length < 4 + 4 + 2 + 4 + 4 + 2 + 32)
            throw new IOException("Truncated flood checkpoint");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        byte[] checksum = Arrays.copyOfRange(bytes, payload.length, bytes.length);
        if (!MessageDigest.isEqual(checksum, sha256(payload)))
            throw new IOException("Flood checkpoint checksum mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
                throw new IOException("Unsupported flood checkpoint version");
            String revision = in.readUTF();
            int count = in.readInt();
            if (count < 1 || count > 4096)
                throw new IOException("Invalid verified outlet count");
            var outlets = new java.util.HashSet<GlobalHydrologyAddressV2.Sample>();
            for (int i = 0; i < count; i++) {
                var address = new GlobalHydrologyAddressV2.Sample(
                    in.readInt(), in.readInt(), in.readLong(), in.readLong());
                if (!outlets.add(address))
                    throw new IOException("Duplicate verified outlet");
            }
            int settled = in.readInt();
            String digest = in.readUTF();
            if (in.available() != 0)
                throw new IOException("Trailing flood checkpoint data");
            return new HydrologyIncrementalPriorityFloodV2.Checkpoint(
                revision, outlets, settled, digest);
        } catch (IllegalArgumentException | EOFException ex) {
            throw new IOException("Invalid flood checkpoint payload", ex);
        }
    }

    private static byte[] encode(
            HydrologyIncrementalPriorityFloodV2.Checkpoint checkpoint)
            throws IOException {
        var body = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(body)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(checkpoint.terrainRevision());
            out.writeInt(checkpoint.verifiedOutlets().size());
            for (var address : checkpoint.verifiedOutlets()) {
                out.writeInt(address.level());
                out.writeInt(address.divisions());
                out.writeLong(address.x());
                out.writeLong(address.y());
            }
            out.writeInt(checkpoint.settledCount());
            out.writeUTF(checkpoint.stateSha256());
        }
        byte[] payload = body.toByteArray();
        if (payload.length + 32 > MAX_BYTES)
            throw new IOException("Flood checkpoint too large");
        var result = new ByteArrayOutputStream(payload.length + 32);
        result.write(payload);
        result.write(sha256(payload));
        return result.toByteArray();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
