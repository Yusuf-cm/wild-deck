package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only finite-volume accounting for a caller-supplied receiving region.
 * Exact decimal accumulation of persisted double receipt amounts makes totals
 * independent of map iteration order. This checks ledger accounting, not the
 * authenticity or completeness of the supplied regional ownership inventory.
 * It does not authorize replay-marker deletion or modify any checkpoint.
 */
public final class HydrologyMixedResolutionWaterBudgetV2 {
    private HydrologyMixedResolutionWaterBudgetV2() {}

    public record Budget(BigDecimal queued, BigDecimal accepted,
                         BigDecimal delivered, BigDecimal awaitingDelivery,
                         BigDecimal unacknowledgedAtSender) {
        public Budget {
            Objects.requireNonNull(queued);
            Objects.requireNonNull(accepted);
            Objects.requireNonNull(delivered);
            Objects.requireNonNull(awaitingDelivery);
            Objects.requireNonNull(unacknowledgedAtSender);
        }
    }

    public record Report(Budget volumes,
                         HydrologyMixedResolutionHistoryAuditV2.Report history) {
        public Report {
            Objects.requireNonNull(volumes);
            Objects.requireNonNull(history);
        }
        /** Requires matching durable receipt provenance, not merely equal totals. */
        public boolean locallyConserved() {
            return history.locallyConsistent() &&
                volumes.queued().compareTo(volumes.accepted()) == 0 &&
                volumes.accepted().compareTo(
                    volumes.delivered().add(volumes.awaitingDelivery())) == 0;
        }
    }

    /**
     * Account for all sender receipts addressed to the supplied physical owners
     * through the inclusive arrival epoch, and the matching receiver inbox.
     * Receipts addressed to other or unloaded regions are excluded, not lost.
     * A clean result cannot prove omitted senders or delayed remote retries.
     */
    public static Report audit(
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint receiver,
            Collection<HydrologyMixedResolutionCheckpointStoreV2.Checkpoint> senders,
            long throughEpoch,
            Collection<GlobalHydrologyAddressV2.Sample> receiverDestinations) {
        var history = HydrologyMixedResolutionHistoryAuditV2.auditForDestinations(
            receiver, senders, throughEpoch, receiverDestinations);
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var sample : receiverDestinations)
            owned.add(HydrologyGlobalCatchmentsV2.coordinate(sample));

        BigDecimal queued = BigDecimal.ZERO;
        BigDecimal unacknowledged = BigDecimal.ZERO;
        for (var sender : senders) {
            for (var entry : sender.outbox().entrySet()) {
                var key = entry.getKey();
                if (key.epoch() > throughEpoch ||
                    !owned.contains(HydrologyGlobalCatchmentsV2.coordinate(key.to())))
                    continue;
                var amount = BigDecimal.valueOf(entry.getValue());
                queued = queued.add(amount);
                if (!sender.acknowledged().contains(key))
                    unacknowledged = unacknowledged.add(amount);
            }
        }

        BigDecimal accepted = BigDecimal.ZERO;
        BigDecimal delivered = BigDecimal.ZERO;
        BigDecimal waiting = BigDecimal.ZERO;
        for (var entry : receiver.inbox().receipts().entrySet()) {
            var key = entry.getKey();
            if (key.epoch() > throughEpoch ||
                !owned.contains(HydrologyGlobalCatchmentsV2.coordinate(key.to())))
                continue;
            var amount = BigDecimal.valueOf(entry.getValue());
            accepted = accepted.add(amount);
            if (receiver.inbox().delivered().contains(key))
                delivered = delivered.add(amount);
            else
                waiting = waiting.add(amount);
        }
        return new Report(new Budget(queued, accepted, delivered, waiting,
                                     unacknowledged), history);
    }
}
