package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Deterministic, retry-safe dispatch of every pending frontier receipt whose
 * geographic destination belongs to a supplied receiving region.
 *
 * Each receipt is accepted and acknowledged using two durable local checkpoints.
 * Earlier successful deliveries remain committed if a later delivery fails;
 * retrying the batch is safe. This is not an atomic multi-file transaction,
 * a route-authentication mechanism, or distributed consensus.
 */
public final class HydrologyMixedResolutionDispatchBatchV2 {
    private HydrologyMixedResolutionDispatchBatchV2() {}

    private static HydrologyGlobalCatchmentsV2.Coordinate coordinate(
            GlobalHydrologyAddressV2.Sample sample) {
        return HydrologyGlobalCatchmentsV2.coordinate(sample);
    }

    private static final Comparator<HydrologyMixedResolutionEpochV2.Receipt> ORDER =
        Comparator.comparingLong(HydrologyMixedResolutionEpochV2.Receipt::epoch)
            .thenComparing(r -> coordinate(r.from()).stableKey())
            .thenComparing(r -> coordinate(r.to()).stableKey());

    public record Result(
            List<HydrologyMixedResolutionEpochV2.Receipt> newlyAcknowledged,
            Map<HydrologyMixedResolutionEpochV2.Receipt, Double> stillPending,
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint sender,
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint receiver) {
        public Result {
            newlyAcknowledged = List.copyOf(newlyAcknowledged);
            stillPending = Map.copyOf(stillPending);
            Objects.requireNonNull(sender);
            Objects.requireNonNull(receiver);
        }
    }

    /**
     * Dispatch only receipts addressed to owned physical vertices. Unknown
     * destinations remain in the sender outbox for other regions or unloaded
     * terrain. Reject duplicate cross-LOD owners before modifying any file.
     *
     * Callers must supply the authoritative receiving graph and use a local
     * filesystem supporting the checkpoint store's cooperative OS locks.
     */
    public static Result dispatchAvailable(Path senderFile, Path receiverFile,
            Collection<HydrologyMixedResolutionDischargeV2.Node> receiverNodes)
            throws IOException {
        Objects.requireNonNull(senderFile);
        Objects.requireNonNull(receiverFile);
        Objects.requireNonNull(receiverNodes);
        if (senderFile.toAbsolutePath().normalize()
                .equals(receiverFile.toAbsolutePath().normalize()) ||
            (Files.exists(senderFile) && Files.exists(receiverFile) &&
             Files.isSameFile(senderFile, receiverFile)))
            throw new IllegalArgumentException("Sender and receiver must differ");

        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var node : receiverNodes) {
            Objects.requireNonNull(node);
            if (!owned.add(coordinate(node.address())))
                throw new IllegalArgumentException("Duplicate geographic receiver owner");
        }

        var initialSender = HydrologyMixedResolutionCheckpointStoreV2.load(senderFile);
        // Audit previously acknowledged receipts before any new deliveries.
        // A missing/replaced receiver checkpoint must not masquerade as a
        // completed batch simply because the sender has no pending receipts.
        var initialReceiver = HydrologyMixedResolutionCheckpointStoreV2.load(receiverFile);
        for (var key : initialSender.acknowledged()) {
            if (!owned.contains(coordinate(key.to()))) continue;
            var expected = initialSender.outbox().get(key);
            var actual = initialReceiver.inbox().receipts().get(key);
            if (actual == null || Double.compare(actual, expected) != 0)
                throw new IOException("Acknowledged water missing or mismatched at receiver");
        }
        var selected = new ArrayList<HydrologyMixedResolutionEpochV2.Receipt>();
        for (var key : HydrologyMixedResolutionCheckpointStoreV2.pending(initialSender).keySet())
            if (owned.contains(coordinate(key.to()))) selected.add(key);
        selected.sort(ORDER);

        var acknowledged = new ArrayList<HydrologyMixedResolutionEpochV2.Receipt>();
        for (var key : selected) {
            // Re-load each side and let the store's locked compare-and-replace
            // reject concurrent stale writes rather than overwriting water.
            var delivery = HydrologyMixedResolutionLocalTransferV2.transfer(
                senderFile, receiverFile, key);
            if (delivery.newlyAcknowledged()) acknowledged.add(key);
        }
        var sender = HydrologyMixedResolutionCheckpointStoreV2.load(senderFile);
        var receiver = HydrologyMixedResolutionCheckpointStoreV2.load(receiverFile);
        return new Result(acknowledged,
            HydrologyMixedResolutionCheckpointStoreV2.pending(sender), sender, receiver);
    }
}
