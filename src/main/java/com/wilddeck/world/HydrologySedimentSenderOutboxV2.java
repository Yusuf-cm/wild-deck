package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Durable, single-writer sediment sender journal. An outgoing transfer MUST be
 * journaled before transmission. Acknowledgment requires an actual persisted
 * receiver checkpoint, but does not delete history: lost receiver checkpoints
 * can be repaired by replaying the original journaled receipt.
 *
 * This is not an authenticated network protocol, distributed transaction,
 * interprocess lock, or permission to compact replay history.
 */
public final class HydrologySedimentSenderOutboxV2 {
    private HydrologySedimentSenderOutboxV2() {}
    private static final String HEADER = "WD-SEDIMENT-OUTBOX-1";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Comparator<HydrologySedimentReceiptInboxV2.Receipt> ORDER =
        Comparator.comparingLong(HydrologySedimentReceiptInboxV2.Receipt::sourceEpoch)
            .thenComparingLong(HydrologySedimentReceiptInboxV2.Receipt::arrivalEpoch)
            .thenComparing(HydrologySedimentReceiptInboxV2.Receipt::from,
                HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(HydrologySedimentReceiptInboxV2.Receipt::to,
                HydrologyFlowGraphV2.ADDRESS_ORDER);
    private record Origin(long epoch, HydrologyGlobalCatchmentsV2.Coordinate position) {}

    public record Snapshot(Map<HydrologySedimentReceiptInboxV2.Receipt,Double> receipts,
                           Set<HydrologySedimentReceiptInboxV2.Receipt> acknowledged) {
        public Snapshot {
            Objects.requireNonNull(receipts);
            Objects.requireNonNull(acknowledged);
            var ordered = new TreeMap<HydrologySedimentReceiptInboxV2.Receipt,Double>(ORDER);
            var origins = new HashMap<Origin,HydrologySedimentReceiptInboxV2.Receipt>();
            for (var entry : receipts.entrySet()) {
                var key = Objects.requireNonNull(entry.getKey());
                mass(entry.getValue());
                var origin = new Origin(key.sourceEpoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(key.from()));
                if (origins.putIfAbsent(origin,key) != null)
                    throw new IllegalArgumentException("Duplicate physical sediment sender");
                ordered.put(key,entry.getValue());
            }
            if (!ordered.keySet().containsAll(acknowledged))
                throw new IllegalArgumentException("Acknowledged transfer absent from sender journal");
            receipts = Collections.unmodifiableMap(ordered);
            acknowledged = Set.copyOf(acknowledged);
        }
    }

    private static BigDecimal mass(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid sediment sender mass");
        return BigDecimal.valueOf(value);
    }

    public static Snapshot empty() { return new Snapshot(Map.of(),Set.of()); }

    /** Retain every transfer, including acknowledged ones, to prevent forks. */
    public static Snapshot append(Snapshot previous,
            HydrologySedimentFrontierTransfersV2.Outgoing outgoing) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(outgoing);
        BigDecimal budget = BigDecimal.ZERO;
        for (var amount : outgoing.receipts().values()) budget = budget.add(mass(amount));
        if (Double.compare(budget.doubleValue(),outgoing.pendingMass()) != 0 ||
            !Double.isFinite(budget.doubleValue()))
            throw new IllegalArgumentException("Sender outbox budget mismatch");
        var combined = new TreeMap<HydrologySedimentReceiptInboxV2.Receipt,Double>(ORDER);
        combined.putAll(previous.receipts());
        for (var entry : outgoing.receipts().entrySet()) {
            var receipt = Objects.requireNonNull(entry.getKey());
            mass(entry.getValue());
            var prior = combined.putIfAbsent(receipt,entry.getValue());
            if (prior != null && Double.compare(prior,entry.getValue()) != 0)
                throw new IllegalArgumentException("Altered sediment sender replay");
        }
        var next = new Snapshot(combined,previous.acknowledged());
        return next.equals(previous) ? previous : next;
    }

    /** An acknowledgment is evidence of receiver persistence, not consumption. */
    public static Snapshot acknowledge(Snapshot previous,
            HydrologySedimentReceiptInboxV2.Receipt receipt,
            HydrologySedimentReceiptInboxV2.Snapshot receiver) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(receipt);
        Objects.requireNonNull(receiver);
        Double sent = previous.receipts().get(receipt);
        if (sent == null || !Objects.equals(sent,receiver.receipts().get(receipt)))
            throw new IllegalArgumentException("Receiver has no matching durable sediment receipt");
        if (previous.acknowledged().contains(receipt)) return previous;
        var marked = new HashSet<>(previous.acknowledged());
        marked.add(receipt);
        return new Snapshot(previous.receipts(),marked);
    }

    /** Includes previously acknowledged receipts lost from a receiver restore. */
    public static Map<HydrologySedimentReceiptInboxV2.Receipt,Double> replayMissing(
            Snapshot sender,HydrologySedimentReceiptInboxV2.Snapshot receiver) {
        Objects.requireNonNull(sender);
        Objects.requireNonNull(receiver);
        var missing = new TreeMap<HydrologySedimentReceiptInboxV2.Receipt,Double>(ORDER);
        for (var entry : sender.receipts().entrySet()) {
            Double found = receiver.receipts().get(entry.getKey());
            if (found == null) missing.put(entry.getKey(),entry.getValue());
            else if (Double.compare(found,entry.getValue()) != 0)
                throw new IllegalArgumentException("Receiver sediment history conflicts with sender");
        }
        return Collections.unmodifiableMap(missing);
    }

    public static HydrologySedimentFrontierTransfersV2.Outgoing outgoing(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        BigDecimal total = BigDecimal.ZERO;
        for (var value : snapshot.receipts().values()) total = total.add(mass(value));
        double sum = total.doubleValue();
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Sender mass overflow");
        return new HydrologySedimentFrontierTransfersV2.Outgoing(snapshot.receipts(),sum);
    }

    public static String encode(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var body = new StringBuilder(HEADER).append('\n');
        for (var entry : snapshot.receipts().entrySet()) {
            var r = entry.getKey();
            var a = r.from();
            var b = r.to();
            body.append("R|").append(r.sourceEpoch()).append('|')
                .append(r.arrivalEpoch()).append('|').append(a.level()).append('|')
                .append(a.divisions()).append('|').append(a.x()).append('|')
                .append(a.y()).append('|').append(b.x()).append('|')
                .append(b.y()).append('|').append(Double.toHexString(entry.getValue()))
                .append('|').append(snapshot.acknowledged().contains(r) ? '1' : '0')
                .append('\n');
        }
        String raw = body.toString();
        String result = raw + digest(raw) + "\n";
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Sender journal exceeds size limit");
        return result;
    }

    public static Snapshot decode(String encoded) {
        Objects.requireNonNull(encoded);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Sender journal exceeds size limit");
        String[] lines = encoded.split("\\n",-1);
        if (lines.length < 3 || !HEADER.equals(lines[0]) ||
            !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Invalid sediment sender journal");
        String body = encoded.substring(0,encoded.lastIndexOf('\n',
            encoded.length()-2)+1);
        if (!digest(body).equals(lines[lines.length-2]))
            throw new IllegalArgumentException("Sender journal checksum mismatch");
        try {
            var receipts = new HashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
            var acknowledged = new HashSet<HydrologySedimentReceiptInboxV2.Receipt>();
            for (int i=1;i<lines.length-2;i++) {
                String[] f = lines[i].split("\\|",-1);
                if (f.length != 11 || !f[0].equals("R") ||
                    !(f[10].equals("0") || f[10].equals("1")))
                    throw new IllegalArgumentException("Invalid sender journal row");
                int level = Integer.parseInt(f[3]);
                int divisions = Integer.parseInt(f[4]);
                var from = new GlobalHydrologyAddressV2.Sample(level,divisions,
                    Long.parseLong(f[5]),Long.parseLong(f[6]));
                var to = new GlobalHydrologyAddressV2.Sample(level,divisions,
                    Long.parseLong(f[7]),Long.parseLong(f[8]));
                var receipt = new HydrologySedimentReceiptInboxV2.Receipt(
                    Long.parseLong(f[1]),Long.parseLong(f[2]),from,to);
                if (receipts.putIfAbsent(receipt,Double.parseDouble(f[9])) != null)
                    throw new IllegalArgumentException("Duplicate sender journal row");
                if (f[10].equals("1")) acknowledged.add(receipt);
            }
            var snapshot = new Snapshot(receipts,acknowledged);
            if (!encode(snapshot).equals(encoded))
                throw new IllegalArgumentException("Noncanonical sediment sender journal");
            return snapshot;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid sender journal number",ex);
        }
    }

    /** A separate checkpoint file, never a mutation of the receiver's state. */
    public static HydrologyCheckpointV2.Checkpoint initialize(Path senderFile)
            throws IOException {
        Objects.requireNonNull(senderFile);
        if (java.nio.file.Files.exists(senderFile))
            throw new IOException("Sender journal already exists");
        var initial = HydrologyCheckpointV2.capture(0,encode(empty()),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(senderFile,initial);
        return initial;
    }

    public static Snapshot restore(HydrologyCheckpointV2.Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint);
        if (!checkpoint.inboxState().equals(HydrologyInboxCodecV2.encode(
                new HydrologyEpochInboxV2().snapshot())))
            throw new IllegalArgumentException("Sender journal contains unexpected water inbox");
        return decode(checkpoint.worldState());
    }

    public static HydrologyCheckpointV2.Checkpoint appendDurable(Path senderFile,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySedimentFrontierTransfersV2.Outgoing outgoing) throws IOException {
        var next = append(restore(expected),outgoing);
        return persist(senderFile,expected,next);
    }

    public static HydrologyCheckpointV2.Checkpoint acknowledgeDurable(Path senderFile,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySedimentReceiptInboxV2.Receipt receipt,Path receiverFile)
            throws IOException {
        var receiver = HydrologyCheckpointStoreV2.load(receiverFile);
        var inbox = HydrologySedimentCheckpointV2.decode(receiver.worldState()).sediment();
        if (inbox.lastClaimedEpoch() > receiver.epoch())
            throw new IllegalArgumentException("Receiver ledger ahead of durable checkpoint");
        var next = acknowledge(restore(expected),receipt,inbox);
        return persist(senderFile,expected,next);
    }

    private static HydrologyCheckpointV2.Checkpoint persist(Path senderFile,
            HydrologyCheckpointV2.Checkpoint expected,Snapshot next) throws IOException {
        var before = restore(expected);
        if (next.equals(before)) {
            if (!HydrologyCheckpointStoreV2.load(senderFile).equals(expected))
                throw new IOException("Stale sediment sender journal");
            return expected;
        }
        var updated = new HydrologyCheckpointV2.Checkpoint(
            Math.addExact(expected.epoch(),1),encode(next),expected.inboxState());
        HydrologyCheckpointStoreV2.saveNext(senderFile,expected,updated);
        return updated;
    }

    private static String digest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}
