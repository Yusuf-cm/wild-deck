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

    private static String sourceIdentity(HydrologyMixedResolutionEpochV2.Receipt key) {
        return key.epoch() + ":" +
            HydrologyGlobalCatchmentsV2.coordinate(key.from()).stableKey();
    }

    /**
     * Examine delivered receipts through an inclusive arrival epoch against
     * available sender checkpoints. Reject missing acknowledgments, mismatched
     * volumes, duplicate sender provenance, conflicting physical source routes,
     * and undelivered old receipts.
     * Receipt epochs are ARRIVAL epochs, so a sender may legitimately queue
     * and acknowledge a future arrival in an earlier committed sender epoch.
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
        var physicalSources = new HashMap<String, HydrologyMixedResolutionEpochV2.Receipt>();
        var forkedSources = new TreeSet<String>();
        for (var sender : senders) {
            Objects.requireNonNull(sender);
            for (var entry : sender.outbox().entrySet()) {
                var key = entry.getKey();
                if (key.epoch() > throughEpoch) continue;
                if (provenance.putIfAbsent(key, entry.getValue()) != null)
                    duplicates.add(key);
                String origin = sourceIdentity(key);
                var prior = physicalSources.putIfAbsent(origin, key);
                if (prior != null && !prior.equals(key))
                    forkedSources.add(origin);
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
            } else if (duplicates.contains(key)) {
                blockers.add("Multiple sender records " + id);
            } else if (!provenance.containsKey(key)) {
                blockers.add("Missing sender record " + id);
            } else if (Double.compare(provenance.get(key), amount) != 0) {
                blockers.add("Sender volume mismatch " + id);
            } else if (!acknowledged.contains(key)) {
                blockers.add("Sender not acknowledged " + id);
            } else if (!forkedSources.contains(sourceIdentity(key))) {
                matched.put(key, amount);
            }
        }
        for (var origin : forkedSources)
            blockers.add("Conflicting sender drainage destinations " + origin);
        return new Report(matched, blockers);
    }

    /**
     * Extend the receiver-first audit with a reverse check of sender records
     * addressed to a caller-supplied set of physical receiver vertices.
     * This detects acknowledged water absent from the receiver, as well as
     * still-in-flight old water that would make pruning unsafe. The caller
     * MUST provide all owned receiving vertices and all relevant senders;
     * this method cannot discover omitted senders or prove a global barrier.
     */
    public static Report auditForDestinations(
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint receiver,
            Collection<HydrologyMixedResolutionCheckpointStoreV2.Checkpoint> senders,
            long throughEpoch,
            Collection<GlobalHydrologyAddressV2.Sample> receiverDestinations) {
        Objects.requireNonNull(receiverDestinations);
        Objects.requireNonNull(senders);
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var sample : receiverDestinations) {
            Objects.requireNonNull(sample);
            if (!owned.add(HydrologyGlobalCatchmentsV2.coordinate(sample)))
                throw new IllegalArgumentException("Duplicate geographic receiver ownership");
        }
        if (owned.isEmpty())
            throw new IllegalArgumentException("Receiver destination inventory is empty");
        var base = audit(receiver, senders, throughEpoch);
        var absent = new HashSet<HydrologyMixedResolutionEpochV2.Receipt>();
        var duplicated = new HashSet<HydrologyMixedResolutionEpochV2.Receipt>();
        var seen = new HashSet<HydrologyMixedResolutionEpochV2.Receipt>();
        for (var sender : senders) {
            for (var key : sender.outbox().keySet()) {
                if (key.epoch() > throughEpoch ||
                    !owned.contains(HydrologyGlobalCatchmentsV2.coordinate(key.to())))
                    continue;
                if (!receiver.inbox().receipts().containsKey(key)) {
                    absent.add(key);
                    if (!seen.add(key)) duplicated.add(key);
                }
            }
        }
        var ordered = new ArrayList<>(absent);
        ordered.sort(Comparator.comparing(HydrologyMixedResolutionHistoryAuditV2::identity));
        var blockers = new ArrayList<>(base.blockers());
        var receiverKeys = new ArrayList<>(receiver.inbox().receipts().keySet());
        receiverKeys.removeIf(key -> key.epoch() > throughEpoch);
        receiverKeys.sort(Comparator.comparing(HydrologyMixedResolutionHistoryAuditV2::identity));
        for (var key : receiverKeys) {
            if (!owned.contains(HydrologyGlobalCatchmentsV2.coordinate(key.to())))
                blockers.add("Receiver receipt outside supplied ownership " + identity(key));
        }
        for (var key : ordered) {
            blockers.add("Sender transfer absent from receiver " + identity(key));
            if (duplicated.contains(key))
                blockers.add("Multiple sender records for absent transfer " + identity(key));
        }
        return new Report(base.locallyMatched(), blockers);
    }
}
