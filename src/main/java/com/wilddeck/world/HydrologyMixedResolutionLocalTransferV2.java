package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
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
                .equals(receiverFile.toAbsolutePath().normalize()))
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
}
