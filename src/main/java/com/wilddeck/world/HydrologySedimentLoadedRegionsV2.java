package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Deterministic, opt-in sediment delivery from one durable sender journal to
 * currently loaded neighboring regions. Unloaded destinations stay journaled.
 *
 * Every loaded region must declare its authoritative physical vertex ownership.
 * All receivers are preflighted before the first write, but commits remain
 * separate per file: concurrent writers, crashes, and I/O errors can leave a
 * partial batch. Retrying the same call converges through durable receipt IDs.
 * This is not an interprocess lock, authenticated transport or global commit.
 */
public final class HydrologySedimentLoadedRegionsV2 {
    private HydrologySedimentLoadedRegionsV2() {}

    public record Receiver(Path checkpoint,
                           List<GlobalHydrologyAddressV2.Sample> ownedVertices) {
        public Receiver {
            Objects.requireNonNull(checkpoint);
            ownedVertices = List.copyOf(Objects.requireNonNull(ownedVertices));
        }
    }

    public record Result(Map<String, HydrologySedimentTransferCoordinatorV2.Delivery> deliveries,
                         int newlyReceived, int newlyAcknowledged,
                         double newlyReceivedMass) {
        public Result {
            Objects.requireNonNull(deliveries);
            deliveries = Collections.unmodifiableMap(new TreeMap<>(deliveries));
            if (newlyReceived < 0 || newlyAcknowledged < 0 ||
                !Double.isFinite(newlyReceivedMass) || newlyReceivedMass < 0)
                throw new IllegalArgumentException("Invalid loaded-region delivery budget");
        }
    }

    private static boolean sameFile(Path left, Path right) throws IOException {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize()) ||
            Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
    }

    /**
     * Preflight every loaded region before committing any receipt. Region keys
     * determine processing order, not map insertion order. This method requires
     * the complete ownership set of each loaded receiver, but not unloaded ones.
     */
    public static Result deliverLoaded(Path senderFile, Map<String, Receiver> loaded)
            throws IOException {
        Objects.requireNonNull(senderFile);
        Objects.requireNonNull(loaded);
        var sender = HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(senderFile));
        var ordered = new TreeMap<String, Receiver>();
        for (var entry : loaded.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid loaded region key");
            ordered.put(entry.getKey(), Objects.requireNonNull(entry.getValue()));
        }

        var claimed = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var files = new ArrayList<Path>();
        BigDecimal preflightMass = BigDecimal.ZERO;
        long preflightCount = 0;
        for (var region : ordered.entrySet()) {
            var receiver = region.getValue();
            if (sameFile(senderFile, receiver.checkpoint()))
                throw new IllegalArgumentException("Sender cannot own a receiving checkpoint");
            for (var previous : files)
                if (sameFile(previous, receiver.checkpoint()))
                    throw new IllegalArgumentException("Two regions share a receiver checkpoint");
            files.add(receiver.checkpoint());

            var local = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
            for (var vertex : receiver.ownedVertices()) {
                if (!HydrologySphericalTopologyV2.isCanonical(vertex))
                    throw new IllegalArgumentException("Noncanonical loaded receiver vertex");
                var physical = HydrologyGlobalCatchmentsV2.coordinate(vertex);
                if (!local.add(physical) || !claimed.add(physical))
                    throw new IllegalArgumentException("Shared physical receiver vertex");
            }

            var checkpoint = HydrologyCheckpointStoreV2.load(receiver.checkpoint());
            var inbox = HydrologySedimentCheckpointV2.decode(checkpoint.worldState()).sediment();
            if (inbox.lastClaimedEpoch() > checkpoint.epoch())
                throw new IllegalArgumentException("Receiver ledger exceeds checkpoint epoch");
            for (var entry : sender.receipts().entrySet()) {
                var receipt = entry.getKey();
                if (!local.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.to())))
                    continue;
                var existing = inbox.receipts().get(receipt);
                if (existing != null && Double.compare(existing, entry.getValue()) != 0)
                    throw new IllegalArgumentException("Conflicting durable sediment receipt");
                if (existing == null) {
                    if (receipt.arrivalEpoch() <= checkpoint.epoch())
                        throw new IllegalStateException("Cannot deliver into closed sediment epoch");
                    preflightMass = preflightMass.add(BigDecimal.valueOf(entry.getValue()));
                    preflightCount++;
                }
            }
        }

        if (preflightCount > Integer.MAX_VALUE ||
            !Double.isFinite(preflightMass.doubleValue()))
            throw new IllegalArgumentException("Loaded sediment delivery budget overflow");

        var deliveries = new TreeMap<String, HydrologySedimentTransferCoordinatorV2.Delivery>();
        int received = 0, acknowledged = 0;
        BigDecimal mass = BigDecimal.ZERO;
        for (var region : ordered.entrySet()) {
            var target = region.getValue();
            var result = HydrologySedimentTransferCoordinatorV2.deliverAndAcknowledge(
                senderFile, target.checkpoint(), target.ownedVertices());
            deliveries.put(region.getKey(), result);
            received = Math.addExact(received, result.newlyReceived());
            acknowledged = Math.addExact(acknowledged, result.newlyAcknowledged());
            mass = mass.add(BigDecimal.valueOf(result.newlyReceivedMass()));
        }
        double total = mass.doubleValue();
        if (!Double.isFinite(total))
            throw new IllegalArgumentException("Loaded sediment delivery mass overflow");
        return new Result(deliveries, received, acknowledged, total);
    }
}
