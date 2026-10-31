package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

/**
 * Retry-safe local two-checkpoint dispatch for a mixed-resolution water edge.
 *
 * Order is deliberate: receiver fsync/rename first, sender acknowledgment
 * second. A crash between those operations leaves an unacknowledged sender
 * receipt which can be replayed without counting water twice.
 *
 * This helper requires single-writer ownership of EACH checkpoint. It is not
 * a distributed transaction, a remote authentication mechanism, or a global
 * terrain/route verifier.
 */
public final class HydrologyMixedResolutionLocalTransferV2 {
    private HydrologyMixedResolutionLocalTransferV2() {}

    public record Delivery(
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint sender,
            HydrologyMixedResolutionCheckpointStoreV2.Checkpoint receiver,
            boolean newlyAcknowledged) {
        public Delivery {
            Objects.requireNonNull(sender);
            Objects.requireNonNull(receiver);
        }
    }

    /**
     * Deliver one sender-queued receipt and acknowledge it only after durable
     * receiver acceptance. Repeat after any IOException or process restart.
     * The same physical file cannot be both sender and receiver.
     */
    public static Delivery transfer(Path senderFile, Path receiverFile,
            HydrologyMixedResolutionEpochV2.Receipt key) throws IOException {
        Objects.requireNonNull(senderFile);
        Objects.requireNonNull(receiverFile);
        Objects.requireNonNull(key);
        if (senderFile.toAbsolutePath().normalize()
                .equals(receiverFile.toAbsolutePath().normalize()) ||
            (Files.exists(senderFile) && Files.exists(receiverFile) &&
             Files.isSameFile(senderFile, receiverFile)))
            throw new IllegalArgumentException("Sender and receiver must be different checkpoints");

        var sender = HydrologyMixedResolutionCheckpointStoreV2.load(senderFile);
        var amount = sender.outbox().get(key);
        if (amount == null)
            throw new IllegalArgumentException("Receipt not present in sender outbox");
        var receiver = HydrologyMixedResolutionCheckpointStoreV2.load(receiverFile);

        if (sender.acknowledged().contains(key)) {
            // Acknowledgment without a matching durable receipt is an integrity
            // violation. Do not silently declare a transfer successful.
            var received = receiver.inbox().receipts().get(key);
            if (received == null || Double.compare(received, amount) != 0)
                throw new IOException("Acknowledged receipt missing or different at receiver");
            return new Delivery(sender, receiver, false);
        }

        var accepted = HydrologyMixedResolutionCheckpointStoreV2.receive(
            receiverFile, receiver, key, amount);
        var acknowledged = HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
            senderFile, sender, key, amount);
        return new Delivery(acknowledged, accepted, true);
    }

    /**
     * Opt-in ownership-checked dispatch for an explicitly verified receiving
     * graph. Rejects a wrong-region receiver before any checkpoint mutation.
     * Cross-resolution aliases are compared by physical coordinates, never
     * tile indices. The caller MUST supply the actual receiver's authoritative
     * graph; this method cannot authenticate that graph or its persisted world.
     */
    public static Delivery transferToVerifiedDestination(Path senderFile,
            Path receiverFile, HydrologyMixedResolutionEpochV2.Receipt key,
            Collection<HydrologyMixedResolutionDischargeV2.Node> receiverNodes)
            throws IOException {
        Objects.requireNonNull(key);
        Objects.requireNonNull(receiverNodes);
        var owners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var node : receiverNodes) {
            Objects.requireNonNull(node);
            if (!owners.add(HydrologyGlobalCatchmentsV2.coordinate(node.address())))
                throw new IllegalArgumentException("Duplicate geographic receiver owner");
        }
        if (!owners.contains(HydrologyGlobalCatchmentsV2.coordinate(key.to())))
            throw new IllegalArgumentException("Receipt does not belong to verified receiver");
        return transfer(senderFile, receiverFile, key);
    }
}
