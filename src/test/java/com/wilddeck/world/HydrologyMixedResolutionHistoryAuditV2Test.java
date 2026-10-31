package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionHistoryAuditV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(2, 4, x, y);
    }
    private static HydrologyMixedResolutionEpochV2.Receipt receipt() {
        return new HydrologyMixedResolutionEpochV2.Receipt(1, at(4, 4), at(5, 4));
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint receiver(
            boolean delivered) {
        var key = receipt();
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(2, "receiver",
            new HydrologyMixedResolutionEpochV2.Snapshot(2, Map.of(key, 7.0),
                delivered ? Set.of(key) : Set.of()));
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint sender(
            double amount, boolean acknowledged) {
        var key = receipt();
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(1, "sender",
            new HydrologyMixedResolutionEpochV2.Snapshot(1, Map.of(), Set.of()),
            Map.of(key, amount), acknowledged ? Set.of(key) : Set.of());
    }

    @Test void deliveredAndAcknowledgedMatchingVolumeIsLocallyConsistent() {
        var report = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(sender(7, true)), 1);
        assertTrue(report.locallyConsistent());
        assertEquals(Map.of(receipt(), 7.0), report.locallyMatched());
        assertThrows(UnsupportedOperationException.class, () ->
            report.locallyMatched().clear());
    }

    @Test void undeliveredOrUnacknowledgedReceiptBlocksRetention() {
        var undelivered = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(false), List.of(sender(7, true)), 1);
        assertFalse(undelivered.locallyConsistent());
        assertTrue(undelivered.blockers().get(0).contains("Undelivered"));
        var unacked = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(sender(7, false)), 1);
        assertFalse(unacked.locallyConsistent());
        assertTrue(unacked.blockers().get(0).contains("not acknowledged"));
    }

    @Test void missingSenderAndVolumeMismatchAreExplicitBlockers() {
        var missing = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(), 1);
        assertTrue(missing.blockers().get(0).contains("Missing sender"));
        var wrong = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(sender(8, true)), 1);
        assertTrue(wrong.blockers().get(0).contains("volume mismatch"));
    }

    @Test void duplicateSenderProvenanceCannotAuthorizeRetention() {
        var report = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(sender(7, true), sender(7, true)), 1);
        assertFalse(report.locallyConsistent());
        assertTrue(report.blockers().get(0).contains("Multiple sender"));
    }

    @Test void senderCanQueueFutureArrivalBeforeItsOwnEpochAdvances() {
        // A sender may commit at epoch 0 and queue a receipt for arrival at
        // epoch 1. The receiver can accept and consume it at epoch 2.
        var key = receipt();
        var queuedAhead = new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            0, "queued-for-next-epoch",
            new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()),
            Map.of(key, 7.0), Set.of(key));
        var report = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(queuedAhead), 1);
        assertTrue(report.locallyConsistent(), report.blockers().toString());
        assertEquals(Map.of(key, 7.0), report.locallyMatched());
        var beforeArrival = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(queuedAhead), 0);
        assertTrue(beforeArrival.locallyMatched().isEmpty());
    }

    @Test void horizonMustBeWithinCommittedReceiverEpoch() {
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionHistoryAuditV2.audit(receiver(true),
                List.of(sender(7, true)), 3));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionHistoryAuditV2.audit(receiver(true),
                List.of(sender(7, true)), -1));
        var none = HydrologyMixedResolutionHistoryAuditV2.audit(
            receiver(true), List.of(), 0);
        assertTrue(none.locallyConsistent());
        assertTrue(none.locallyMatched().isEmpty());
    }

    @Test void reverseAuditDetectsAcknowledgedWaterMissingAtReceiver() {
        var empty = new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            2, "receiver", new HydrologyMixedResolutionEpochV2.Snapshot(
                2, Map.of(), Set.of()));
        var report = HydrologyMixedResolutionHistoryAuditV2.auditForDestinations(
            empty, List.of(sender(7, true)), 1, List.of(at(5, 4)));
        assertFalse(report.locallyConsistent());
        assertTrue(report.blockers().stream().anyMatch(
            b -> b.contains("absent from receiver")));
        assertTrue(report.locallyMatched().isEmpty());
    }

    @Test void reverseAuditDetectsPendingWaterButRespectsEpochHorizon() {
        var empty = new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            2, "receiver", new HydrologyMixedResolutionEpochV2.Snapshot(
                2, Map.of(), Set.of()));
        var oldPending = HydrologyMixedResolutionHistoryAuditV2.auditForDestinations(
            empty, List.of(sender(7, false)), 1, List.of(at(5, 4)));
        assertFalse(oldPending.locallyConsistent());
        var beforeArrival = HydrologyMixedResolutionHistoryAuditV2.auditForDestinations(
            empty, List.of(sender(7, false)), 0, List.of(at(5, 4)));
        assertTrue(beforeArrival.locallyConsistent(), beforeArrival.blockers().toString());
    }

    @Test void reverseAuditDoesNotRequireReceiptsForOtherRegions() {
        var key = receipt();
        var other = new HydrologyMixedResolutionEpochV2.Receipt(
            1, at(6, 4), at(7, 4));
        var checkpoint = new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            1, "sender",
            new HydrologyMixedResolutionEpochV2.Snapshot(1, Map.of(), Set.of()),
            Map.of(key, 7.0, other, 9.0), Set.of(key, other));
        var report = HydrologyMixedResolutionHistoryAuditV2.auditForDestinations(
            receiver(true), List.of(checkpoint), 1, List.of(at(5, 4)));
        assertTrue(report.locallyConsistent(), report.blockers().toString());
        assertEquals(Map.of(key, 7.0), report.locallyMatched());
    }

    @Test void reverseAuditRejectsAmbiguousReceiverOwnership() {
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionHistoryAuditV2.auditForDestinations(
                receiver(true), List.of(sender(7, true)), 1,
                List.of(at(5, 4), at(5, 4))));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionHistoryAuditV2.auditForDestinations(
                receiver(true), List.of(sender(7, true)), 1, List.of()));
    }
}
