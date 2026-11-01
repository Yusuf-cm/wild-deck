package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in, deterministic multi-sender delivery to currently loaded sediment
 * regions. All sender histories must be supplied, including senders with only
 * unloaded destinations. A read-only preflight rejects ownership, provenance,
 * closed-epoch and conservation conflicts before the first receiver write.
 *
 * Each receiver is checkpointed before sender acknowledgments. Checkpoints are
 * NOT a multi-file transaction: a crash or concurrent writer after preflight
 * can leave a partial delivery, which must be retried using the same durable
 * sender journals. There is no authentication or interprocess locking here.
 */
public final class HydrologySedimentLoadedNetworkV2 {
    private HydrologySedimentLoadedNetworkV2() {}

    private record Origin(long epoch, HydrologyGlobalCatchmentsV2.Coordinate location) {}

    public record Plan(int pendingReceipts, int pendingAcknowledgments,
                       double pendingMass,
                       HydrologySedimentCrossRegionAuditV2.Report before) {
        public Plan {
            Objects.requireNonNull(before);
            if (pendingReceipts < 0 || pendingAcknowledgments < 0 ||
                !Double.isFinite(pendingMass) || pendingMass < 0)
                throw new IllegalArgumentException("Invalid sediment network preflight budget");
        }
    }

    public record Result(Map<String, HydrologySedimentTransferCoordinatorV2.Recovered> receivers,
                         int newlyReceived, double newlyReceivedMass,
                         HydrologySedimentCrossRegionAuditV2.Report after) {
        public Result {
            Objects.requireNonNull(receivers);
            Objects.requireNonNull(after);
            receivers = Collections.unmodifiableMap(new TreeMap<>(receivers));
            if (newlyReceived < 0 || !Double.isFinite(newlyReceivedMass) ||
                newlyReceivedMass < 0)
                throw new IllegalArgumentException("Invalid sediment network delivery budget");
        }
    }

    private static boolean sameFile(Path a, Path b) throws IOException {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize()) ||
            Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
    }

    private static <T> TreeMap<String, T> ordered(Map<String, T> input) {
        Objects.requireNonNull(input);
        var result = new TreeMap<String, T>();
        for (var entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid sediment region key");
            result.put(entry.getKey(), Objects.requireNonNull(entry.getValue()));
        }
        return result;
    }

    private static void distinct(Path path, Collection<Path> prior) throws IOException {
        for (var previous : prior)
            if (sameFile(path, previous))
                throw new IllegalArgumentException("Sediment checkpoint file has multiple owners");
    }

    /**
     * Read-only preflight. The caller must prevent concurrent writes between
     * preflight and delivery; this plan is evidence, not a transactional lock.
     */
    public static Plan plan(Map<String, Path> senderFiles,
            Map<String, HydrologySedimentLoadedRegionsV2.Receiver> loaded)
            throws IOException {
        var senders = ordered(senderFiles);
        var receivers = ordered(loaded);
        var paths = new ArrayList<Path>();
        var snapshots = new TreeMap<String, HydrologySedimentSenderOutboxV2.Snapshot>();
        var receipts = new HashMap<HydrologySedimentReceiptInboxV2.Receipt, Double>();
        var origins = new HashMap<Origin, HydrologySedimentReceiptInboxV2.Receipt>();
        for (var sender : senders.entrySet()) {
            Path path = sender.getValue();
            distinct(path, paths);
            paths.add(path);
            var snapshot = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(path));
            snapshots.put(sender.getKey(), snapshot);
            for (var transfer : snapshot.receipts().entrySet()) {
                var receipt = transfer.getKey();
                if (receipts.putIfAbsent(receipt, transfer.getValue()) != null)
                    throw new IllegalArgumentException("Duplicate sediment receipt across senders");
                var origin = new Origin(receipt.sourceEpoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(receipt.from()));
                if (origins.putIfAbsent(origin, receipt) != null)
                    throw new IllegalArgumentException("Cross-sender physical sediment source fork");
            }
        }

        var claimed = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var receiverPaths = new TreeMap<String, Path>();
        BigDecimal pendingMass = BigDecimal.ZERO;
        long pendingCount = 0, pendingAcknowledgments = 0;
        for (var region : receivers.entrySet()) {
            var receiver = region.getValue();
            distinct(receiver.checkpoint(), paths);
            paths.add(receiver.checkpoint());
            receiverPaths.put(region.getKey(), receiver.checkpoint());
            var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
            for (var address : receiver.ownedVertices()) {
                if (!HydrologySphericalTopologyV2.isCanonical(address))
                    throw new IllegalArgumentException("Noncanonical sediment receiver vertex");
                var physical = HydrologyGlobalCatchmentsV2.coordinate(address);
                if (!owned.add(physical) || !claimed.add(physical))
                    throw new IllegalArgumentException("Shared physical sediment receiver vertex");
            }
            var checkpoint = HydrologyCheckpointStoreV2.load(receiver.checkpoint());
            var inbox = HydrologySedimentCheckpointV2.decode(checkpoint.worldState()).sediment();
            if (inbox.lastClaimedEpoch() > checkpoint.epoch())
                throw new IllegalArgumentException("Sediment receiver ledger exceeds checkpoint epoch");
            for (var existing : inbox.receipts().entrySet()) {
                if (!owned.contains(HydrologyGlobalCatchmentsV2.coordinate(existing.getKey().to())) ||
                    !Objects.equals(receipts.get(existing.getKey()), existing.getValue()))
                    throw new IllegalArgumentException("Unowned or unproven sediment receiver history");
            }
            for (var sender : snapshots.values()) {
                for (var transfer : sender.receipts().entrySet()) {
                    var receipt = transfer.getKey();
                    if (!owned.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.to())))
                        continue;
                    if (!sender.acknowledged().contains(receipt)) pendingAcknowledgments++;
                    if (inbox.receipts().containsKey(receipt)) continue;
                    if (receipt.arrivalEpoch() <= checkpoint.epoch())
                        throw new IllegalStateException("Cannot restore sediment into closed epoch");
                    pendingCount++;
                    pendingMass = pendingMass.add(BigDecimal.valueOf(transfer.getValue()));
                }
            }
        }
        if (pendingCount > Integer.MAX_VALUE || pendingAcknowledgments > Integer.MAX_VALUE ||
            !Double.isFinite(pendingMass.doubleValue()))
            throw new IllegalArgumentException("Sediment network preflight overflow");
        var before = HydrologySedimentCrossRegionAuditV2.reconcileAllDurable(
            senders, receiverPaths);
        return new Plan((int)pendingCount, (int)pendingAcknowledgments,
            pendingMass.doubleValue(), before);
    }

    /**
     * Deliver in sorted receiver order. Each receiver gets one durable batch
     * from the complete sender set, then each sender is acknowledged. A retry
     * after an interrupted write must reuse the original sender histories.
     */
    public static Result deliver(Map<String, Path> senderFiles,
            Map<String, HydrologySedimentLoadedRegionsV2.Receiver> loaded)
            throws IOException {
        var senders = ordered(senderFiles);
        var receivers = ordered(loaded);
        plan(senders, receivers);
        var committed = new TreeMap<String, HydrologySedimentTransferCoordinatorV2.Recovered>();
        var receiverPaths = new TreeMap<String, Path>();
        BigDecimal mass = BigDecimal.ZERO;
        int count = 0;
        for (var region : receivers.entrySet()) {
            var target = region.getValue();
            var result = HydrologySedimentTransferCoordinatorV2.recoverAndAcknowledge(
                target.checkpoint(), senders, target.ownedVertices());
            committed.put(region.getKey(), result);
            receiverPaths.put(region.getKey(), target.checkpoint());
            count = Math.addExact(count, result.newlyReceived());
            mass = mass.add(BigDecimal.valueOf(result.newlyReceivedMass()));
        }
        if (!Double.isFinite(mass.doubleValue()))
            throw new IllegalArgumentException("Sediment network committed mass overflow");
        var after = HydrologySedimentCrossRegionAuditV2.reconcileAllDurable(
            senders, receiverPaths);
        return new Result(committed, count, mass.doubleValue(), after);
    }
}
