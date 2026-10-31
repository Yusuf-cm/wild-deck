package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionWaterBudgetV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(2, 4, x, y);
    }

    private static HydrologyMixedResolutionEpochV2.Receipt first() {
        return new HydrologyMixedResolutionEpochV2.Receipt(1, at(4, 4), at(5, 4));
    }

    private static HydrologyMixedResolutionEpochV2.Receipt second() {
        return new HydrologyMixedResolutionEpochV2.Receipt(1, at(4, 5), at(5, 4));
    }

    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint sender(
            Map<HydrologyMixedResolutionEpochV2.Receipt, Double> outbox,
            Set<HydrologyMixedResolutionEpochV2.Receipt> acknowledged) {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            1, "sender", new HydrologyMixedResolutionEpochV2.Snapshot(
                1, Map.of(), Set.of()), outbox, acknowledged);
    }

    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint receiver(
            Map<HydrologyMixedResolutionEpochV2.Receipt, Double> receipts,
            Set<HydrologyMixedResolutionEpochV2.Receipt> delivered) {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            2, "receiver", new HydrologyMixedResolutionEpochV2.Snapshot(
                2, receipts, delivered));
    }

    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test void twoSourceConfluenceHasExactOrderIndependentVolumeAccounting() {
        var r1 = first();
        var r2 = second();
        var receiving = receiver(Map.of(r1, 7.0, r2, 11.0), Set.of(r1, r2));
        var s1 = sender(Map.of(r1, 7.0), Set.of(r1));
        var s2 = sender(Map.of(r2, 11.0), Set.of(r2));
        var forward = HydrologyMixedResolutionWaterBudgetV2.audit(
            receiving, List.of(s1, s2), 1, List.of(at(5, 4)));
        var reversed = HydrologyMixedResolutionWaterBudgetV2.audit(
            receiving, List.of(s2, s1), 1, List.of(at(5, 4)));
        assertTrue(forward.locallyConserved(), forward.history().blockers().toString());
        assertEquals(forward.volumes(), reversed.volumes());
        amount("18.0", forward.volumes().queued());
        amount("18.0", forward.volumes().accepted());
        amount("18.0", forward.volumes().delivered());
        amount("0", forward.volumes().awaitingDelivery());
        amount("0", forward.volumes().unacknowledgedAtSender());
    }

    @Test void missingReceiverWaterIsNotHiddenBySenderAcknowledgment() {
        var r1 = first();
        var r2 = second();
        var receiving = receiver(Map.of(r1, 7.0), Set.of(r1));
        var sending = sender(Map.of(r1, 7.0, r2, 11.0), Set.of(r1, r2));
        var report = HydrologyMixedResolutionWaterBudgetV2.audit(
            receiving, List.of(sending), 1, List.of(at(5, 4)));
        assertFalse(report.locallyConserved());
        assertTrue(report.history().blockers().stream().anyMatch(
            b -> b.contains("absent from receiver")));
        amount("18.0", report.volumes().queued());
        amount("7.0", report.volumes().accepted());
    }

    @Test void acceptedButUndeliveredAndUnacknowledgedWaterStaysVisible() {
        var r1 = first();
        var r2 = second();
        var receiving = receiver(Map.of(r1, 7.0, r2, 11.0), Set.of(r1));
        var sending = sender(Map.of(r1, 7.0, r2, 11.0), Set.of(r1));
        var report = HydrologyMixedResolutionWaterBudgetV2.audit(
            receiving, List.of(sending), 1, List.of(at(5, 4)));
        assertFalse(report.locallyConserved());
        amount("18.0", report.volumes().accepted());
        amount("7.0", report.volumes().delivered());
        amount("11.0", report.volumes().awaitingDelivery());
        amount("11.0", report.volumes().unacknowledgedAtSender());
    }

    @Test void unloadedOtherRegionAndFutureArrivalAreExcludedFromHorizon() {
        var r1 = first();
        var other = new HydrologyMixedResolutionEpochV2.Receipt(
            1, at(6, 4), at(7, 4));
        var future = new HydrologyMixedResolutionEpochV2.Receipt(
            3, at(4, 5), at(5, 4));
        var receiving = receiver(Map.of(r1, 7.0, future, 19.0), Set.of(r1));
        var sending = sender(Map.of(r1, 7.0, other, 13.0, future, 19.0),
                             Set.of(r1, other));
        var report = HydrologyMixedResolutionWaterBudgetV2.audit(
            receiving, List.of(sending), 1, List.of(at(5, 4)));
        assertTrue(report.locallyConserved(), report.history().blockers().toString());
        amount("7.0", report.volumes().queued());
        amount("7.0", report.volumes().accepted());
    }

    @Test void mixedResolutionReceiverAliasDoesNotCreateSecondWaterOwner() {
        var r1 = first();
        var receiving = receiver(Map.of(r1, 7.0), Set.of(r1));
        var sending = sender(Map.of(r1, 7.0), Set.of(r1));
        var alias = new GlobalHydrologyAddressV2.Sample(3, 4, 10, 8);
        var report = HydrologyMixedResolutionWaterBudgetV2.audit(
            receiving, List.of(sending), 1, List.of(alias));
        assertTrue(report.locallyConserved(), report.history().blockers().toString());
        amount("7.0", report.volumes().accepted());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionWaterBudgetV2.audit(
                receiving, List.of(sending), 1, List.of(at(5, 4), alias)));
    }

    @Test void senderForkCannotPassBudgetEvenWhenVolumesHappenToMatch() {
        var r1 = first();
        var fork = new HydrologyMixedResolutionEpochV2.Receipt(
            1, at(4, 4), at(4, 5));
        var receiving = receiver(Map.of(r1, 7.0), Set.of(r1));
        var s1 = sender(Map.of(r1, 7.0), Set.of(r1));
        var s2 = sender(Map.of(fork, 7.0), Set.of(fork));
        var report = HydrologyMixedResolutionWaterBudgetV2.audit(
            receiving, List.of(s1, s2), 1, List.of(at(5, 4)));
        assertFalse(report.locallyConserved());
        assertTrue(report.history().blockers().stream().anyMatch(
            b -> b.contains("Conflicting sender drainage destinations")));
    }
}
