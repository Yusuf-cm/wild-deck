package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in local-file transport of already journaled cross-region sediment.
 *
 * The sender journal must be committed before calling this coordinator.
 * A receiver checkpoint is committed before its sender acknowledgment. A crash
 * between these writes is safe to retry: the receiver recognizes exact replays
 * and the sender rechecks durable receiver evidence before acknowledging.
 *
 * The caller must provide authoritative physical receiver ownership and, for
 * multi-sender repair, the COMPLETE set of relevant sender journals. This is
 * not authenticated transport, a distributed transaction, or a multi-process
 * lock. Closed receiving epochs cannot be reconstructed by replay.
 */
public final class HydrologySedimentTransferCoordinatorV2 {
    private HydrologySedimentTransferCoordinatorV2() {}

    private static final Comparator<HydrologySedimentReceiptInboxV2.Receipt> ORDER =
        Comparator.comparingLong(HydrologySedimentReceiptInboxV2.Receipt::arrivalEpoch)
            .thenComparingLong(HydrologySedimentReceiptInboxV2.Receipt::sourceEpoch)
            .thenComparing(HydrologySedimentReceiptInboxV2.Receipt::from,
                HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(HydrologySedimentReceiptInboxV2.Receipt::to,
                HydrologyFlowGraphV2.ADDRESS_ORDER);

    public record Delivery(HydrologyCheckpointV2.Checkpoint receiver,
                           HydrologyCheckpointV2.Checkpoint sender,
                           int newlyReceived, int newlyAcknowledged,
                           double newlyReceivedMass) {
        public Delivery {
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(sender);
            if (newlyReceived < 0 || newlyAcknowledged < 0 ||
                !Double.isFinite(newlyReceivedMass) || newlyReceivedMass < 0)
                throw new IllegalArgumentException("Invalid sediment delivery budget");
        }
    }

    public record Recovered(HydrologyCheckpointV2.Checkpoint receiver,
                            Map<String, HydrologyCheckpointV2.Checkpoint> senders,
                            int newlyReceived, double newlyReceivedMass) {
        public Recovered {
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(senders);
            if (newlyReceived < 0 || !Double.isFinite(newlyReceivedMass) ||
                newlyReceivedMass < 0)
                throw new IllegalArgumentException("Invalid sediment recovery budget");
            senders = Collections.unmodifiableMap(new TreeMap<>(senders));
        }
    }

    private static Set<HydrologyGlobalCatchmentsV2.Coordinate> owners(
            Collection<GlobalHydrologyAddressV2.Sample> addresses) {
        Objects.requireNonNull(addresses);
        var result = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var address : addresses) {
            Objects.requireNonNull(address);
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                !result.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Duplicate or invalid physical receiver owner");
        }
        return result;
    }

    private static void distinctFiles(Path receiver, Collection<Path> senders)
            throws IOException {
        Objects.requireNonNull(receiver);
        for (var sender : senders) {
            Objects.requireNonNull(sender);
            if (receiver.toAbsolutePath().normalize().equals(
                    sender.toAbsolutePath().normalize()) ||
                Files.exists(receiver) && Files.exists(sender) &&
                    Files.isSameFile(receiver, sender))
                throw new IllegalArgumentException("Sender and receiver checkpoints must differ");
        }
    }

    private static Map<HydrologySedimentReceiptInboxV2.Receipt, Double> forOwners(
            HydrologySedimentSenderOutboxV2.Snapshot sender,
            Set<HydrologyGlobalCatchmentsV2.Coordinate> owned) {
        var selected = new TreeMap<HydrologySedimentReceiptInboxV2.Receipt, Double>(ORDER);
        for (var entry : sender.receipts().entrySet())
            if (owned.contains(HydrologyGlobalCatchmentsV2.coordinate(entry.getKey().to())))
                selected.put(entry.getKey(), entry.getValue());
        return selected;
    }

    /**
     * Durable receive, followed by receiver-backed sender acknowledgment.
     * Never invent transfers: only journaled receipts may be delivered.
     * No unrelated destination in the sender journal is acknowledged.
     */
    public static Delivery deliverAndAcknowledge(
            Path senderFile, Path receiverFile,
            Collection<GlobalHydrologyAddressV2.Sample> ownedDestinations)
            throws IOException {
        distinctFiles(receiverFile, List.of(senderFile));
        var owned = owners(ownedDestinations);
        var senderBefore = HydrologyCheckpointStoreV2.load(senderFile);
        var journal = HydrologySedimentSenderOutboxV2.restore(senderBefore);
        var selected = forOwners(journal, owned);
        var receiverBefore = HydrologyCheckpointStoreV2.load(receiverFile);
        var inbox = HydrologySedimentCheckpointV2.decode(
            receiverBefore.worldState()).sediment();
        if (inbox.lastClaimedEpoch() > receiverBefore.epoch())
            throw new IllegalArgumentException("Receiver sediment ledger is ahead of checkpoint");

        int newCount = 0;
        BigDecimal newMass = BigDecimal.ZERO;
        for (var entry : selected.entrySet()) {
            var receipt = entry.getKey();
            var existing = inbox.receipts().get(receipt);
            if (existing != null) {
                if (Double.compare(existing, entry.getValue()) != 0)
                    throw new IllegalArgumentException("Conflicting durable sediment receipt");
            } else {
                if (receipt.arrivalEpoch() <= receiverBefore.epoch())
                    throw new IllegalStateException("Cannot deliver into a closed sediment epoch");
                newCount++;
                newMass = newMass.add(BigDecimal.valueOf(entry.getValue()));
            }
        }
        double mass = newMass.doubleValue();
        if (!Double.isFinite(mass))
            throw new IllegalArgumentException("Sediment delivery mass overflow");

        // A failure here leaves the sender untouched. If the later sender write
        // fails, a retry sees the persisted receipts and acknowledges them.
        var receiverAfter = HydrologySedimentCheckpointV2.receiveBatch(
            receiverFile, receiverBefore, selected);
        int newAcknowledgments = 0;
        for (var receipt : selected.keySet())
            if (!journal.acknowledged().contains(receipt)) newAcknowledgments++;
        var senderAfter = HydrologySedimentSenderOutboxV2.acknowledgeBatchDurable(
            senderFile, senderBefore, selected.keySet(), receiverFile);
        return new Delivery(receiverAfter, senderAfter,
            newCount, newAcknowledgments, mass);
    }

    /**
     * Reconcile all sender histories into a receiver, then durably acknowledge
     * each sender. Receiver repair is one local checkpoint write; acknowledgments
     * are separate local writes, so partial acknowledgments require retry.
     */
    public static Recovered recoverAndAcknowledge(
            Path receiverFile, Map<String, Path> senderFiles,
            Collection<GlobalHydrologyAddressV2.Sample> ownedDestinations)
            throws IOException {
        Objects.requireNonNull(senderFiles);
        var owned = owners(ownedDestinations);
        distinctFiles(receiverFile, senderFiles.values());
        // Fail closed on duplicate aliases to one physical sender journal.
        var files = new ArrayList<Path>();
        for (var file : senderFiles.values()) {
            for (var prior : files)
                if (file.toAbsolutePath().normalize().equals(
                        prior.toAbsolutePath().normalize()) ||
                    Files.exists(file) && Files.exists(prior) &&
                        Files.isSameFile(file, prior))
                    throw new IllegalArgumentException("Duplicate physical sender journal");
            files.add(file);
        }
        var repaired = HydrologySedimentReceiverRecoveryV2.repairBatch(
            receiverFile, senderFiles, ownedDestinations);
        var acknowledged = new TreeMap<String, HydrologyCheckpointV2.Checkpoint>();
        for (var sender : new TreeMap<>(senderFiles).entrySet()) {
            var before = HydrologyCheckpointStoreV2.load(sender.getValue());
            var journal = HydrologySedimentSenderOutboxV2.restore(before);
            var selected = forOwners(journal, owned);
            acknowledged.put(sender.getKey(),
                HydrologySedimentSenderOutboxV2.acknowledgeBatchDurable(
                    sender.getValue(), before, selected.keySet(), receiverFile));
        }
        return new Recovered(repaired.checkpoint(), acknowledged,
            repaired.restoredCount(), repaired.restoredMass());
    }
}
