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
 * Incoming receipts, outgoing transfers, acknowledgment markers, and the
 * caller's world state share one checksummed, atomically replaced file.
 * A receiver must durably accept a receipt before the sender acknowledges it.
 * This is NOT distributed consensus, authentication, multi-process CAS,
 * or a cross-region two-phase commit.
 */
public final class HydrologyMixedResolutionCheckpointStoreV2 {
    private HydrologyMixedResolutionCheckpointStoreV2() {}
    private static final int MAGIC = 0x4d524332; // MRC2
    private static final int VERSION = 2;
    private static final int MAX_FIELD = 16 * 1024 * 1024;
    private static final int MAX_FILE = 3 * MAX_FIELD + 128;

    public record Checkpoint(long epoch, String worldState,
            HydrologyMixedResolutionEpochV2.Snapshot inbox,
            Map<HydrologyMixedResolutionEpochV2.Receipt, Double> outbox,
            Set<HydrologyMixedResolutionEpochV2.Receipt> acknowledged) {
        /** Compatibility constructor for checkpoints without outgoing water. */
        public Checkpoint(long epoch, String worldState,
                HydrologyMixedResolutionEpochV2.Snapshot inbox) {
            this(epoch, worldState, inbox, Map.of(), Set.of());
        }
        public Checkpoint {
            if (epoch < 0) throw new IllegalArgumentException("Invalid checkpoint epoch");
            Objects.requireNonNull(worldState);
            Objects.requireNonNull(inbox);
            if (inbox.advancedThrough() != epoch)
                throw new IllegalArgumentException("World and receipt epochs differ");
            // Reuse the receipt ledger's canonical geographic identity, finite
            // volume, unique source, and delivered-subset validation.
            var validated = new HydrologyMixedResolutionEpochV2.Snapshot(
                Long.MAX_VALUE, Objects.requireNonNull(outbox),
                Objects.requireNonNull(acknowledged));
            outbox = validated.receipts();
            acknowledged = validated.delivered();
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
            HydrologyMixedResolutionEpochV2.receive(expected.inbox(), receipt, volume),
            expected.outbox(), expected.acknowledged());
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
        // A plain world advance does not queue new frontier transfers, but it
        // must never erase transfers queued by earlier epochs.
        var next = new Checkpoint(epoch, nextWorld, staged.next(),
            expected.outbox(), expected.acknowledged());
        saveExpected(file, expected, next);
        return new Committed(staged.flow(), next);
    }

    /**
     * Advance the sender's world and persist every frontier transfer in the
     * SAME atomic checkpoint. Pending transfers survive crashes and restarts.
     * The arrival epoch must not precede the sender's newly committed epoch.
     * A dispatcher may retry pending receipts until the receiving checkpoint
     * has durably accepted them, then call acknowledgePersisted.
     */
    public static Committed advanceAndQueue(Path file, Checkpoint expected,
            String nextWorld, Collection<HydrologyMixedResolutionDischargeV2.Node> nodes,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths,
            long arrivalEpoch) throws IOException {
        Objects.requireNonNull(expected);
        long epoch = Math.addExact(expected.epoch(), 1);
        if (arrivalEpoch < epoch)
            throw new IllegalArgumentException("Arrival precedes sender epoch");
        var staged = HydrologyMixedResolutionEpochV2.prepare(
            expected.inbox(), epoch, nodes, verifiedMouths);
        var exported = HydrologyMixedResolutionEpochV2.exportFrontier(
            arrivalEpoch, nodes, staged.flow());
        var queued = new HashMap<>(expected.outbox());
        for (var entry : exported.entrySet())
            if (queued.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IllegalArgumentException("Outgoing receipt identity reused");
        var next = new Checkpoint(epoch, nextWorld, staged.next(),
            queued, expected.acknowledged());
        saveExpected(file, expected, next);
        return new Committed(staged.flow(), next);
    }

    /** All unacknowledged transfers, including those from previous epochs. */
    public static Map<HydrologyMixedResolutionEpochV2.Receipt, Double> pending(
            Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint);
        var pending = new LinkedHashMap<HydrologyMixedResolutionEpochV2.Receipt, Double>();
        for (var entry : checkpoint.outbox().entrySet())
            if (!checkpoint.acknowledged().contains(entry.getKey()))
                pending.put(entry.getKey(), entry.getValue());
        return Collections.unmodifiableMap(pending);
    }

    /**
     * Acknowledge ONLY after the receiver has durably persisted the exact
     * receipt and amount. The caller is responsible for checking that fact;
     * this local store cannot authenticate a remote receiver. Duplicate
     * acknowledgments are idempotent, and history is retained for replay safety.
     */
    public static Checkpoint acknowledgePersisted(Path file, Checkpoint expected,
            HydrologyMixedResolutionEpochV2.Receipt key, double amount) throws IOException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(key);
        var recorded = expected.outbox().get(key);
        if (recorded == null || Double.compare(recorded, amount) != 0)
            throw new IllegalArgumentException("Unknown or mismatched outgoing receipt");
        if (expected.acknowledged().contains(key)) {
            saveExpected(file, expected, expected);
            return expected;
        }
        var acked = new HashSet<>(expected.acknowledged());
        acked.add(key);
        var next = new Checkpoint(expected.epoch(), expected.worldState(),
            expected.inbox(), expected.outbox(), acked);
        saveExpected(file, expected, next);
        return next;
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
        if (!next.outbox().entrySet().containsAll(expected.outbox().entrySet()) ||
            !next.acknowledged().containsAll(expected.acknowledged()))
            throw new IOException("Cannot erase queued or acknowledged water");
        if (next.epoch() == expected.epoch() &&
            (!next.worldState().equals(expected.worldState()) ||
             !next.inbox().delivered().equals(expected.inbox().delivered()) ||
             !next.outbox().equals(expected.outbox())))
            throw new IOException("Same-epoch receipt/ack update cannot change world or outbox");
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
            if (in.readInt() != MAGIC)
                throw new IOException("Unknown checkpoint format");
            int version = in.readInt();
            if (version != 1 && version != VERSION)
                throw new IOException("Unknown checkpoint version");
            long epoch = in.readLong();
            int worldSize = in.readInt();
            if (worldSize < 0 || worldSize > MAX_FIELD || worldSize > in.available())
                throw new IOException("Invalid world length");
            String world = new String(in.readNBytes(worldSize), StandardCharsets.UTF_8);
            int inboxSize = in.readInt();
            if (inboxSize < 0 || inboxSize > MAX_FIELD || inboxSize > in.available())
                throw new IOException("Invalid receipt length");
            String inbox = new String(in.readNBytes(inboxSize), StandardCharsets.US_ASCII);
            var accepted = HydrologyMixedResolutionEpochV2.decode(inbox);
            if (version == 1) {
                if (in.available() != 0) throw new IOException("Trailing legacy checkpoint data");
                return new Checkpoint(epoch, world, accepted);
            }
            if (in.available() < 4) throw new IOException("Missing outgoing ledger");
            int outboxSize = in.readInt();
            if (outboxSize < 0 || outboxSize > MAX_FIELD || outboxSize != in.available())
                throw new IOException("Invalid outgoing ledger length");
            String outbox = new String(in.readNBytes(outboxSize), StandardCharsets.US_ASCII);
            var outgoing = HydrologyMixedResolutionEpochV2.decode(outbox);
            if (outgoing.advancedThrough() != Long.MAX_VALUE)
                throw new IOException("Invalid outgoing ledger version");
            return new Checkpoint(epoch, world, accepted,
                outgoing.receipts(), outgoing.delivered());
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
        byte[] outbox = HydrologyMixedResolutionEpochV2.encode(
            new HydrologyMixedResolutionEpochV2.Snapshot(
                Long.MAX_VALUE, checkpoint.outbox(), checkpoint.acknowledged()))
            .getBytes(StandardCharsets.US_ASCII);
        if (world.length > MAX_FIELD || inbox.length > MAX_FIELD || outbox.length > MAX_FIELD)
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
            out.writeInt(outbox.length);
            out.write(outbox);
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
