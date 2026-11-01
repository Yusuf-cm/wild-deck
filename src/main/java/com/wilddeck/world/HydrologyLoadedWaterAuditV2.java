package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only water receipt conservation audit across a complete caller-owned
 * loaded region set. Counts each durable transfer ONCE at its physical source,
 * even when neighboring regions use different sampling resolutions.
 *
 * This validates receipt identity, ownership and local accepted/delivered
 * mass, not the existence of an external sender outbox or a planetary water
 * budget. No history may be deleted on the strength of this audit.
 */
public final class HydrologyLoadedWaterAuditV2 {
    private HydrologyLoadedWaterAuditV2() {}

    private record Origin(long epoch,HydrologyGlobalCatchmentsV2.Coordinate from) {}

    public record Report(BigDecimal accepted,BigDecimal delivered,
                         BigDecimal pending,int acceptedCount,
                         int deliveredCount,int pendingCount) {
        public Report {
            Objects.requireNonNull(accepted);
            Objects.requireNonNull(delivered);
            Objects.requireNonNull(pending);
            if (accepted.signum() < 0 || delivered.signum() < 0 ||
                pending.signum() < 0 || acceptedCount < 0 ||
                deliveredCount < 0 || pendingCount < 0 ||
                acceptedCount != deliveredCount + pendingCount ||
                accepted.compareTo(delivered.add(pending)) != 0)
                throw new IllegalArgumentException("Invalid water receipt conservation");
        }
        public boolean settled() { return pendingCount == 0; }
    }

    private static boolean sameFile(Path left,Path right) throws IOException {
        return left.toAbsolutePath().normalize().equals(
            right.toAbsolutePath().normalize()) ||
            Files.exists(left) && Files.exists(right) && Files.isSameFile(left,right);
    }

    public static Report audit(
            Map<String,HydrologySedimentLoadedRegionsV2.Receiver> loaded)
            throws IOException {
        Objects.requireNonNull(loaded);
        var owners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var paths = new ArrayList<Path>();
        var sources = new HashMap<Origin,HydrologyTransferLedgerV2.Key>();
        var receipts = new HashSet<HydrologyTransferLedgerV2.Key>();
        BigDecimal accepted = BigDecimal.ZERO,delivered = BigDecimal.ZERO,
            pending = BigDecimal.ZERO;
        int total = 0,consumed = 0,waiting = 0;

        for (var region : new TreeMap<>(loaded).entrySet()) {
            if (region.getKey() == null || region.getKey().isBlank())
                throw new IllegalArgumentException("Invalid water receiver region key");
            var receiver = Objects.requireNonNull(region.getValue());
            for (var path : paths)
                if (sameFile(path,receiver.checkpoint()))
                    throw new IllegalArgumentException("Aliased water receiver checkpoint");
            paths.add(receiver.checkpoint());
            var local = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
            for (var vertex : receiver.ownedVertices()) {
                if (!HydrologySphericalTopologyV2.isCanonical(vertex))
                    throw new IllegalArgumentException("Noncanonical water owner");
                var location = HydrologyGlobalCatchmentsV2.coordinate(vertex);
                if (!local.add(location) || !owners.add(location))
                    throw new IllegalArgumentException("Duplicate physical water owner");
            }
            var checkpoint = HydrologyCheckpointStoreV2.load(receiver.checkpoint());
            var inbox = HydrologyInboxCodecV2.decode(checkpoint.inboxState());
            for (var epoch : inbox.consumedEpochs())
                if (epoch > checkpoint.epoch())
                    throw new IllegalArgumentException("Water claims uncommitted epoch");
            for (var entry : inbox.receipts().entrySet()) {
                var key = entry.getKey();
                if (!HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                    !HydrologySphericalTopologyV2.isCanonical(key.to()) ||
                    !local.contains(HydrologyGlobalCatchmentsV2.coordinate(key.to())))
                    throw new IllegalArgumentException("Unowned or noncanonical water arrival");
                if (!receipts.add(key))
                    throw new IllegalArgumentException("Water receipt duplicated across regions");
                var origin = new Origin(key.epoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(key.from()));
                if (sources.putIfAbsent(origin,key) != null)
                    throw new IllegalArgumentException("Forked physical water source");
                var amount = BigDecimal.valueOf(entry.getValue());
                accepted = accepted.add(amount);
                total = Math.addExact(total,1);
                if (inbox.delivered().contains(key)) {
                    if (key.epoch() > checkpoint.epoch())
                        throw new IllegalArgumentException("Water delivered before arrival epoch");
                    delivered = delivered.add(amount);
                    consumed = Math.addExact(consumed,1);
                } else {
                    pending = pending.add(amount);
                    waiting = Math.addExact(waiting,1);
                }
            }
        }
        return new Report(accepted,delivered,pending,total,consumed,waiting);
    }

    /** Strict, read-only gate for topology changes after all receipts settle. */
    public static Report requireSettled(
            Map<String,HydrologySedimentLoadedRegionsV2.Receiver> loaded)
            throws IOException {
        var result = audit(loaded);
        if (!result.settled())
            throw new IllegalStateException("Unsettled cross-region water receipts");
        return result;
    }
}
