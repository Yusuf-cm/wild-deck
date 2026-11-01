package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in, read-only preflight and deterministic dispatch for durable
 * mixed-resolution WATER across multiple senders and loaded receivers.
 *
 * Geographic vertices have exactly one loaded receiver owner. Every supplied
 * sender outbox and loaded receiver inbox is audited BEFORE the first write,
 * including previously acknowledged receipts. Unloaded destinations remain
 * queued in their sender outboxes. A retry after receiver persistence but
 * before sender acknowledgment does not reinject water.
 *
 * This is NOT an atomic distributed transaction, a global source inventory,
 * authenticated provenance, or a concurrency barrier. The caller must supply
 * complete sender histories and serialize all writers for the duration.
 */
public final class HydrologyMixedResolutionLoadedNetworkV2 {
    private HydrologyMixedResolutionLoadedNetworkV2() {}

    public record Receiver(Path checkpoint,
            List<GlobalHydrologyAddressV2.Sample> ownedVertices) {
        public Receiver {
            Objects.requireNonNull(checkpoint);
            ownedVertices = List.copyOf(Objects.requireNonNull(ownedVertices));
        }
    }

    public record Transfer(String sender, String receiver,
            HydrologyMixedResolutionEpochV2.Receipt receipt, double volume) {
        public Transfer {
            Objects.requireNonNull(sender);
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(receipt);
            if (!Double.isFinite(volume) || volume < 0)
                throw new IllegalArgumentException("Invalid network water volume");
        }
    }

    public record Report(BigDecimal exported, BigDecimal loaded, BigDecimal unloaded,
            BigDecimal accepted, BigDecimal acknowledged,
            int transferCount, int pendingLoadedCount, int unloadedCount) {
        public Report {
            Objects.requireNonNull(exported);
            Objects.requireNonNull(loaded);
            Objects.requireNonNull(unloaded);
            Objects.requireNonNull(accepted);
            Objects.requireNonNull(acknowledged);
            if (exported.signum() < 0 || loaded.signum() < 0 ||
                unloaded.signum() < 0 || accepted.signum() < 0 ||
                acknowledged.signum() < 0 ||
                exported.compareTo(loaded.add(unloaded)) != 0 ||
                accepted.compareTo(loaded) > 0 ||
                acknowledged.compareTo(accepted) > 0 ||
                transferCount < 0 || pendingLoadedCount < 0 ||
                unloadedCount < 0 ||
                pendingLoadedCount + unloadedCount > transferCount)
                throw new IllegalArgumentException("Invalid network water balance");
        }
        public boolean loadedSettled() { return pendingLoadedCount == 0; }
    }

    public record Plan(List<Transfer> pending, Report report) {
        public Plan {
            pending = List.copyOf(Objects.requireNonNull(pending));
            Objects.requireNonNull(report);
            if (pending.size() != report.pendingLoadedCount())
                throw new IllegalArgumentException("Pending network plan mismatch");
        }
    }

    public record Result(Plan before, Plan after, int newlyAcknowledged) {
        public Result {
            Objects.requireNonNull(before);
            Objects.requireNonNull(after);
            if (newlyAcknowledged < 0 || newlyAcknowledged > before.pending().size())
                throw new IllegalArgumentException("Invalid acknowledgment count");
        }
    }

    private record Origin(long epoch, HydrologyGlobalCatchmentsV2.Coordinate source) {}

    private static HydrologyGlobalCatchmentsV2.Coordinate coordinate(
            GlobalHydrologyAddressV2.Sample sample) {
        return HydrologyGlobalCatchmentsV2.coordinate(sample);
    }

    private static final Comparator<Transfer> ORDER =
        Comparator.comparingLong((Transfer t) -> t.receipt().epoch())
            .thenComparing(t -> coordinate(t.receipt().from()).stableKey())
            .thenComparing(t -> coordinate(t.receipt().to()).stableKey())
            .thenComparing(Transfer::sender)
            .thenComparing(Transfer::receiver);

    private static boolean sameFile(Path a, Path b) throws IOException {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize()) ||
            Files.exists(a) && Files.exists(b) && Files.isSameFile(a,b);
    }

    private static <T> TreeMap<String,T> ordered(Map<String,T> input) {
        Objects.requireNonNull(input);
        var result = new TreeMap<String,T>();
        for (var entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid water network region key");
            result.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        return result;
    }

    private static void distinct(Path path, Collection<Path> prior) throws IOException {
        for (var earlier : prior)
            if (sameFile(path,earlier))
                throw new IllegalArgumentException("Aliased water network checkpoint");
    }

    /**
     * Validate every supplied sender and loaded receiver before writing.
     * A sender/receiver checkpoint may not be listed twice, even under a
     * symlink. Unloaded destinations are intentionally not fabricated.
     */
    public static Plan plan(Map<String,Path> senderFiles,
                            Map<String,Receiver> loaded) throws IOException {
        var senders = ordered(senderFiles);
        var receivers = ordered(loaded);
        var paths = new ArrayList<Path>();
        var sources = new HashMap<Origin,HydrologyMixedResolutionEpochV2.Receipt>();
        var outboxes = new HashMap<HydrologyMixedResolutionEpochV2.Receipt,Double>();
        var senderByReceipt = new HashMap<HydrologyMixedResolutionEpochV2.Receipt,String>();
        var snapshots = new TreeMap<String,HydrologyMixedResolutionCheckpointStoreV2.Checkpoint>();

        for (var entry : senders.entrySet()) {
            distinct(entry.getValue(),paths);
            paths.add(entry.getValue());
            var state = HydrologyMixedResolutionCheckpointStoreV2.load(entry.getValue());
            snapshots.put(entry.getKey(),state);
            for (var outgoing : state.outbox().entrySet()) {
                var key = outgoing.getKey();
                if (outboxes.putIfAbsent(key,outgoing.getValue()) != null)
                    throw new IllegalArgumentException("Duplicate physical water transfer");
                var origin = new Origin(key.epoch(),coordinate(key.from()));
                if (sources.putIfAbsent(origin,key) != null)
                    throw new IllegalArgumentException("Forked physical water source");
                senderByReceipt.put(key,entry.getKey());
            }
        }

        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,String>();
        var inboxes = new TreeMap<String,HydrologyMixedResolutionCheckpointStoreV2.Checkpoint>();
        for (var entry : receivers.entrySet()) {
            var receiver = entry.getValue();
            distinct(receiver.checkpoint(),paths);
            paths.add(receiver.checkpoint());
            for (var vertex : receiver.ownedVertices()) {
                if (!HydrologySphericalTopologyV2.isCanonical(vertex))
                    throw new IllegalArgumentException("Noncanonical water receiver owner");
                if (owners.putIfAbsent(coordinate(vertex),entry.getKey()) != null)
                    throw new IllegalArgumentException("Duplicate physical water receiver owner");
            }
            var checkpoint = HydrologyMixedResolutionCheckpointStoreV2.load(receiver.checkpoint());
            inboxes.put(entry.getKey(),checkpoint);
            for (var received : checkpoint.inbox().receipts().entrySet()) {
                var key = received.getKey();
                if (!entry.getKey().equals(owners.get(coordinate(key.to()))))
                    throw new IllegalArgumentException("Water receipt outside receiver ownership");
                var exported = outboxes.get(key);
                if (exported == null || Double.compare(exported,received.getValue()) != 0)
                    throw new IOException("Unproven or mismatched durable water receipt");
            }
        }

        BigDecimal exported = BigDecimal.ZERO, assigned = BigDecimal.ZERO,
            unloaded = BigDecimal.ZERO, accepted = BigDecimal.ZERO,
            acknowledged = BigDecimal.ZERO;
        int count = 0, unloadedCount = 0;
        var pending = new ArrayList<Transfer>();
        for (var sender : snapshots.entrySet()) {
            for (var outgoing : sender.getValue().outbox().entrySet()) {
                var key = outgoing.getKey();
                var amount = BigDecimal.valueOf(outgoing.getValue());
                exported = exported.add(amount);
                count = Math.addExact(count,1);
                var region = owners.get(coordinate(key.to()));
                if (region == null) {
                    // An acknowledged transfer must still have a known durable
                    // destination in this complete loaded receiver inventory.
                    // Unloaded destinations may remain queued, never acknowledged.
                    if (sender.getValue().acknowledged().contains(key))
                        throw new IOException("Acknowledged water has no loaded receiver");
                    unloaded = unloaded.add(amount);
                    unloadedCount = Math.addExact(unloadedCount,1);
                    continue;
                }
                assigned = assigned.add(amount);
                var receiver = inboxes.get(region);
                var received = receiver.inbox().receipts().get(key);
                if (received != null) {
                    if (Double.compare(received,outgoing.getValue()) != 0)
                        throw new IOException("Receiver water amount mismatch");
                    accepted = accepted.add(amount);
                }
                if (sender.getValue().acknowledged().contains(key)) {
                    if (received == null)
                        throw new IOException("Acknowledged water missing at receiver");
                    acknowledged = acknowledged.add(amount);
                } else {
                    pending.add(new Transfer(sender.getKey(),region,key,outgoing.getValue()));
                }
            }
        }
        pending.sort(ORDER);
        return new Plan(pending,new Report(exported,assigned,unloaded,
            accepted,acknowledged,count,pending.size(),unloadedCount));
    }

    /**
     * Dispatch each preflighted receipt in canonical order. The receiver is
     * persisted before its sender acknowledgment. After a crash, retry with
     * the same complete inventory; already received water is not counted twice.
     */
    public static Result deliver(Map<String,Path> senderFiles,
                                 Map<String,Receiver> loaded) throws IOException {
        var senders = ordered(senderFiles);
        var receivers = ordered(loaded);
        var before = plan(senders,receivers);
        int newlyAcknowledged = 0;
        for (var transfer : before.pending()) {
            var delivery = HydrologyMixedResolutionLocalTransferV2.transfer(
                senders.get(transfer.sender()),
                receivers.get(transfer.receiver()).checkpoint(),
                transfer.receipt());
            if (delivery.newlyAcknowledged())
                newlyAcknowledged = Math.addExact(newlyAcknowledged,1);
        }
        var after = plan(senders,receivers);
        if (!after.report().loadedSettled())
            throw new IOException("Water delivery incomplete or concurrent sender changed");
        return new Result(before,after,newlyAcknowledged);
    }
}
