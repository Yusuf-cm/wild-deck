package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Strict, read-only reconciliation of a COMPLETE set of sediment frontier
 * outboxes against receiving ledgers. A missing receiver receipt is in transit;
 * a received but unclaimed receipt is awaiting delivery. Shared geographic
 * vertices have exactly one receiving-region owner, regardless of resolution.
 *
 * This audit does not transmit, authenticate, acknowledge, or compact receipts.
 * The caller must supply every relevant sender outbox and receiver ledger;
 * partial snapshots are intentionally rejected when they contain unknown
 * inbound receipts. It is not a replacement for durable sender outboxes.
 */
public final class HydrologySedimentCrossRegionAuditV2 {
    private HydrologySedimentCrossRegionAuditV2() {}

    private record Origin(long epoch, HydrologyGlobalCatchmentsV2.Coordinate location) {}

    public record Report(double sentMass, double deliveredMass,
                         double awaitingMass, double inTransitMass,
                         int sentCount, int deliveredCount,
                         int awaitingCount, int inTransitCount) {
        public Report {
            if (!Double.isFinite(sentMass) || !Double.isFinite(deliveredMass) ||
                !Double.isFinite(awaitingMass) || !Double.isFinite(inTransitMass) ||
                sentMass < 0 || deliveredMass < 0 ||
                awaitingMass < 0 || inTransitMass < 0 ||
                sentCount < 0 || deliveredCount < 0 ||
                awaitingCount < 0 || inTransitCount < 0 ||
                sentCount != deliveredCount + awaitingCount + inTransitCount)
                throw new IllegalArgumentException("Invalid sediment reconciliation report");
        }
    }

    private static BigDecimal mass(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid sediment transfer mass");
        return BigDecimal.valueOf(value);
    }

    private static double finite(BigDecimal value) {
        double result = value.doubleValue();
        if (!Double.isFinite(result))
            throw new IllegalArgumentException("Sediment reconciliation mass overflow");
        return result;
    }

    /**
     * An outbox entry is counted ONCE, at the sender. A receiving ledger
     * changes only its state (in transit / awaiting / delivered), never the
     * total physical sediment. Distinct senders may converge at one receiver.
     */
    public static Report reconcile(
            Map<String, HydrologySedimentFrontierTransfersV2.Outgoing> outboxes,
            Map<String, HydrologySedimentReceiptInboxV2.Snapshot> inboxes) {
        Objects.requireNonNull(outboxes);
        Objects.requireNonNull(inboxes);
        var sent = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        var origins = new HashMap<Origin, HydrologySedimentReceiptInboxV2.Receipt>();
        BigDecimal total = BigDecimal.ZERO;
        for (var region : new TreeMap<>(outboxes).entrySet()) {
            if (region.getKey() == null || region.getValue() == null)
                throw new IllegalArgumentException("Null sender region");
            BigDecimal regionTotal = BigDecimal.ZERO;
            for (var entry : region.getValue().receipts().entrySet()) {
                var receipt = Objects.requireNonNull(entry.getKey());
                BigDecimal amount = mass(entry.getValue());
                if (sent.putIfAbsent(receipt, entry.getValue()) != null)
                    throw new IllegalArgumentException("Duplicate sediment outbox receipt");
                var origin = new Origin(receipt.sourceEpoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(receipt.from()));
                var prior = origins.putIfAbsent(origin, receipt);
                if (prior != null)
                    throw new IllegalArgumentException("Cross-region sediment sender fork");
                regionTotal = regionTotal.add(amount);
            }
            if (Double.compare(finite(regionTotal), region.getValue().pendingMass()) != 0)
                throw new IllegalArgumentException("Sender sediment outbox budget mismatch");
            total = total.add(regionTotal);
        }

        var seen = new HashSet<HydrologySedimentReceiptInboxV2.Receipt>();
        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate, String>();
        BigDecimal delivered = BigDecimal.ZERO, awaiting = BigDecimal.ZERO;
        int deliveredCount = 0, awaitingCount = 0;
        for (var region : new TreeMap<>(inboxes).entrySet()) {
            if (region.getKey() == null || region.getValue() == null)
                throw new IllegalArgumentException("Null receiving region");
            for (var entry : region.getValue().receipts().entrySet()) {
                var receipt = entry.getKey();
                Double expected = sent.get(receipt);
                if (expected == null || Double.compare(expected, entry.getValue()) != 0)
                    throw new IllegalArgumentException("Unmatched or altered sediment receipt");
                if (!seen.add(receipt))
                    throw new IllegalArgumentException("Sediment receipt counted in two regions");
                var location = HydrologyGlobalCatchmentsV2.coordinate(receipt.to());
                String previousOwner = owners.putIfAbsent(location, region.getKey());
                if (previousOwner != null && !previousOwner.equals(region.getKey()))
                    throw new IllegalArgumentException("Shared geographic receiver has multiple owners");
                if (region.getValue().delivered().contains(receipt)) {
                    delivered = delivered.add(mass(entry.getValue()));
                    deliveredCount++;
                } else {
                    awaiting = awaiting.add(mass(entry.getValue()));
                    awaitingCount++;
                }
            }
        }
        BigDecimal inTransit = total.subtract(delivered).subtract(awaiting);
        if (inTransit.signum() < 0)
            throw new IllegalArgumentException("Sediment receiving exceeds dispatched mass");
        return new Report(finite(total), finite(delivered), finite(awaiting),
            finite(inTransit), sent.size(), deliveredCount, awaitingCount,
            sent.size() - seen.size());
    }

    /**
     * Read the actual checksummed receiving checkpoint files before auditing.
     * A missing checkpoint is not interpreted as a zero-mass receiver: callers
     * must explicitly omit regions that have never received a transfer.
     */
    public static Report reconcileDurable(
            Map<String, HydrologySedimentFrontierTransfersV2.Outgoing> outboxes,
            Map<String, Path> receivingCheckpoints) throws IOException {
        Objects.requireNonNull(receivingCheckpoints);
        var inboxes = new TreeMap<String, HydrologySedimentReceiptInboxV2.Snapshot>();
        for (var region : receivingCheckpoints.entrySet()) {
            Objects.requireNonNull(region.getKey());
            var checkpoint = HydrologyCheckpointStoreV2.load(
                Objects.requireNonNull(region.getValue()));
            var ledger = HydrologySedimentCheckpointV2.decode(
                checkpoint.worldState()).sediment();
            if (ledger.lastClaimedEpoch() > checkpoint.epoch())
                throw new IllegalArgumentException("Sediment ledger claims future checkpoint epoch");
            inboxes.put(region.getKey(), ledger);
        }
        return reconcile(outboxes, inboxes);
    }
    /**
     * Audit actual sender AND receiver checkpoints. Unlike reconcileDurable,
     * this does not trust caller-supplied outbox maps. All relevant sender
     * files must be supplied; missing or corrupted files fail closed.
     */
    public static Report reconcileAllDurable(
            Map<String, Path> sendingCheckpoints,
            Map<String, Path> receivingCheckpoints) throws IOException {
        Objects.requireNonNull(sendingCheckpoints);
        Objects.requireNonNull(receivingCheckpoints);
        var outboxes = new TreeMap<String,HydrologySedimentFrontierTransfersV2.Outgoing>();
        for (var entry : sendingCheckpoints.entrySet()) {
            String region = Objects.requireNonNull(entry.getKey());
            var checkpoint = HydrologyCheckpointStoreV2.load(
                Objects.requireNonNull(entry.getValue()));
            var snapshot = HydrologySedimentSenderOutboxV2.restore(checkpoint);
            outboxes.put(region,HydrologySedimentSenderOutboxV2.outgoing(snapshot));
        }
        return reconcileDurable(outboxes,receivingCheckpoints);
    }

}
