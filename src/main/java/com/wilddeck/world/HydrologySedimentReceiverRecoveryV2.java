package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in, single-writer recovery of missing sediment receipts from complete
 * durable sender journals. All candidate receipts are checked before one
 * receiver checkpoint write. The caller supplies authoritative ownership of
 * the receiver's geographic vertices, including unloaded destinations.
 *
 * Sender journals are never modified. This is not sender authentication,
 * distributed atomicity, or permission to rewind a closed receiver epoch.
 */
public final class HydrologySedimentReceiverRecoveryV2 {
    private HydrologySedimentReceiverRecoveryV2() {}

    private record Origin(long epoch, HydrologyGlobalCatchmentsV2.Coordinate location) {}

    public record Result(HydrologyCheckpointV2.Checkpoint checkpoint,
                         int restoredCount, double restoredMass) {
        public Result {
            Objects.requireNonNull(checkpoint);
            if (restoredCount < 0 || !Double.isFinite(restoredMass) || restoredMass < 0)
                throw new IllegalArgumentException("Invalid sediment recovery budget");
        }
    }

    /**
     * Recover an unloaded or restored receiving region from ALL relevant sender
     * journals. Other destinations in those journals are ignored. Missing
     * receipts whose arrival epoch is already closed are rejected, not silently
     * delivered or used to rewrite past terrain/water state.
     */
    public static Result repairBatch(Path receiverFile,
            Map<String, Path> senderFiles,
            Collection<GlobalHydrologyAddressV2.Sample> ownedDestinations)
            throws IOException {
        Objects.requireNonNull(receiverFile);
        Objects.requireNonNull(senderFiles);
        Objects.requireNonNull(ownedDestinations);
        var owners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var address : ownedDestinations) {
            Objects.requireNonNull(address);
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                !owners.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Invalid or duplicate physical receiver ownership");
        }

        var receiver = HydrologyCheckpointStoreV2.load(receiverFile);
        var inbox = HydrologySedimentCheckpointV2.decode(receiver.worldState()).sediment();
        if (inbox.lastClaimedEpoch() > receiver.epoch())
            throw new IllegalArgumentException("Receiver sediment ledger is ahead of checkpoint");

        var all = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        var origins = new HashMap<Origin, HydrologySedimentReceiptInboxV2.Receipt>();
        for (var sender : new TreeMap<>(senderFiles).entrySet()) {
            Objects.requireNonNull(sender.getKey());
            var snapshot = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(Objects.requireNonNull(sender.getValue())));
            for (var entry : snapshot.receipts().entrySet()) {
                var receipt = entry.getKey();
                if (all.putIfAbsent(receipt, entry.getValue()) != null)
                    throw new IllegalArgumentException("Sediment receipt appears in multiple sender journals");
                var origin = new Origin(receipt.sourceEpoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(receipt.from()));
                if (origins.putIfAbsent(origin, receipt) != null)
                    throw new IllegalArgumentException("Cross-journal physical sediment sender fork");
            }
        }
        // A complete sender set is required: never accept an unknown receiver
        // receipt and then falsely report that the repair is reconciled.
        for (var entry : inbox.receipts().entrySet()) {
            if (!owners.contains(HydrologyGlobalCatchmentsV2.coordinate(entry.getKey().to())))
                throw new IllegalArgumentException("Receiver ledger contains a foreign destination");
            if (!Objects.equals(all.get(entry.getKey()), entry.getValue()))
                throw new IllegalArgumentException("Receiver receipt lacks matching durable sender");
        }

        var missing = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        BigDecimal mass = BigDecimal.ZERO;
        for (var entry : all.entrySet()) {
            var receipt = entry.getKey();
            if (!owners.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.to())))
                continue;
            if (inbox.receipts().containsKey(receipt)) continue;
            if (receipt.arrivalEpoch() <= receiver.epoch())
                throw new IllegalStateException("Cannot repair sediment into an already closed epoch");
            missing.put(receipt, entry.getValue());
            mass = mass.add(BigDecimal.valueOf(entry.getValue()));
        }
        double restoredMass = mass.doubleValue();
        if (!Double.isFinite(restoredMass))
            throw new IllegalArgumentException("Sediment recovery mass overflow");
        var committed = HydrologySedimentCheckpointV2.receiveBatch(receiverFile, receiver, missing);
        return new Result(committed, missing.size(), restoredMass);
    }
}
