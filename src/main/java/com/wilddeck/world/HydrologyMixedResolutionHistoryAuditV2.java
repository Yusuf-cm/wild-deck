package com.wilddeck.world;

import java.util.*;

/**
 * Read-only audit for receipt-history retention planning. A delivered receiver
 * receipt is locally matched only if a supplied sender checkpoint still holds
 * the identical outgoing amount and a durable acknowledgment.
 *
 * This does NOT compact any ledger. In particular, a collection of sender
 * snapshots cannot prove that all senders, delayed retries, backups, or remote
 * processes have crossed a replay barrier. Deleting delivered receipts solely
 * because this audit passes would be unsafe.
 */
public final class HydrologyMixedResolutionHistoryAuditV2 {
    private HydrologyMixedResolutionHistoryAuditV2() {}

    public record Report(
            Map<HydrologyMixedResolutionEpochV2.Receipt, Double> locallyMatched,
            List<String> blockers) {
        public Report {
            locallyMatched = Collections.unmodifiableMap(new LinkedHashMap<>(locallyMatched));
            blockers = List.copyOf(blockers);
        }
        public boolean locallyConsistent() { return blockers.isEmpty(); }
    }

    private static String identity(HydrologyMixedResolutionEpochV2.Receipt key) {
        return key.epoch() + ":" +
            HydrologyGlobalCatchmentsV2.coordinate(key.from()).stableKey() + ">" +
            HydrologyGlobalCatchmentsV2.coordinate(key.to()).stableKey();
    }

    /**
     * Examine delivered receipts through an inclusive arrival epoch against
     * available sender checkpoints. Reject missing acknowledgments, mismatched
     * volumes, duplicate sender provenance and undelivered old receipts.
     * Even an empty blocker list is NOT authorization to prune replay markers.
     */
    public static Report audit(
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint receiver,
            Collection<HydrologyMixedResolutionCheckpointStoreV2.Checkpoint> senders,
            long throughEpoch) {
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(senders);
        if (throughEpoch < 0 || throughEpoch > receiver.epoch())
            throw new IllegalArgumentException("Audit horizon outside committed receiver epochs");

        var provenance = new HashMap<HydrologyMixedResolutionEpochV2.Receipt, Double>();
        var acknowledged = new HashSet<HydrologyMixedResolutionEpochV2.Receipt>();
        var duplicates = new HashSet<HydrologyMixedResolutionEpochV2.Receipt>();
        var premature = new HashSet<HydrologyMixedResolutionEpochV2.Receipt>();
        for (var sender : senders) {
            Objects.requireNonNull(sender);
            for (var entry : sender.outbox().entrySet()) {
                var key = entry.getKey();
                if (key.epoch() > throughEpoch) continue;
                if (sender.epoch() < key.epoch()) premature.add(key);
                if (provenance.putIfAbsent(key, entry.getValue()) != null)
                    duplicates.add(key);
                if (sender.acknowledged().contains(key)) acknowledged.add(key);
            }
        }

        var ordered = new ArrayList<>(receiver.inbox().receipts().keySet());
        ordered.removeIf(key -> key.epoch() > throughEpoch);
        ordered.sort(Comparator.comparing(HydrologyMixedResolutionHistoryAuditV2::identity));
        var matched = new LinkedHashMap<HydrologyMixedResolutionEpochV2.Receipt, Double>();
        var blockers = new ArrayList<String>();
        for (var key : ordered) {
            String id = identity(key);
            double amount = receiver.inbox().receipts().get(key);
            if (!receiver.inbox().delivered().contains(key)) {
                blockers.add("Undelivered receipt " + id);
            } else if (premature.contains(key)) {
                blockers.add("Sender checkpoint predates receipt " + id);
            } else if (duplicates.contains(key)) {
                blockers.add("Multiple sender records " + id);
            } else if (!provenance.containsKey(key)) {
                blockers.add("Missing sender record " + id);
            } else if (Double.compare(provenance.get(key), amount) != 0) {
                blockers.add("Sender volume mismatch " + id);
            } else if (!acknowledged.contains(key)) {
                blockers.add("Sender not acknowledged " + id);
            } else {
                matched.put(key, amount);
            }
        }
        return new Report(matched, blockers);
    }
}
