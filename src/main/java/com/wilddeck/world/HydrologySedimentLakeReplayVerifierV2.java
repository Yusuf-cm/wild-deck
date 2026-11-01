package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Exact-state replay preflight for a loaded lake region. The original
 * checksummed checkpoint and complete durable sender histories must be
 * retained across an interrupted multi-region operation.
 *
 * Reconstructs the original epoch's staged receipts, simulates the requested
 * lake/terrain step without writing, and compares the entire expected next
 * checkpoint (including lake volume, bed geometry and receipt markers).
 * This is not a distributed lock or proof of authenticated senders.
 */
public final class HydrologySedimentLakeReplayVerifierV2 {
    private HydrologySedimentLakeReplayVerifierV2() {}

    public record Verified(HydrologyCheckpointV2.Checkpoint expectedNext,
                           boolean completed) {
        public Verified {
            Objects.requireNonNull(expectedNext);
        }
    }

    public static Verified verify(
            HydrologyCheckpointV2.Checkpoint original,
            HydrologyCheckpointV2.Checkpoint actual,
            Map<String,Path> senders,
            HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep step,
            long targetEpoch) throws IOException {
        Objects.requireNonNull(original);
        Objects.requireNonNull(actual);
        Objects.requireNonNull(senders);
        Objects.requireNonNull(step);
        if (targetEpoch < 1 || original.epoch() != targetEpoch - 1)
            throw new IllegalArgumentException("Lake replay baseline epoch mismatch");

        var initial = HydrologySedimentCheckpointV2.decode(original.worldState());
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var vertex : step.receiver().ownedVertices()) {
            if (!HydrologySphericalTopologyV2.isCanonical(vertex) ||
                !owned.add(HydrologyGlobalCatchmentsV2.coordinate(vertex)))
                throw new IllegalArgumentException("Duplicate or noncanonical lake receiver");
        }

        var staged = initial.sediment();
        for (var sender : new TreeMap<>(senders).entrySet()) {
            var outbox = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(sender.getValue()));
            for (var transfer : outbox.receipts().entrySet()) {
                if (owned.contains(HydrologyGlobalCatchmentsV2.coordinate(
                        transfer.getKey().to())))
                    staged = HydrologySedimentReceiptInboxV2.receive(
                        staged,transfer.getKey(),transfer.getValue());
            }
        }

        var received = new HydrologyCheckpointV2.Checkpoint(
            original.epoch(),HydrologySedimentCheckpointV2.encode(
                initial.world(),staged),original.inboxState());
        var expected = HydrologySedimentCheckpointV2.planLakeWithTerrain(
            received,step.baseline(),step.nextWorld(),step.seed(),
            step.water(),step.capacity(),step.dryBulkDensity(),step.mode()).next();
        if (expected.epoch() != targetEpoch)
            throw new IllegalStateException("Lake replay simulation epoch mismatch");

        if (actual.epoch() == targetEpoch) {
            if (!actual.equals(expected))
                throw new IllegalArgumentException(
                    "Completed lake checkpoint differs from exact requested epoch");
            return new Verified(expected,true);
        }
        if (actual.epoch() != original.epoch() ||
            !actual.inboxState().equals(original.inboxState()))
            throw new IllegalArgumentException("Lake replay checkpoint epoch/inbox changed");

        var current = HydrologySedimentCheckpointV2.decode(actual.worldState());
        if (!current.world().equals(initial.world()) ||
            current.sediment().lastClaimedEpoch() != initial.sediment().lastClaimedEpoch() ||
            !current.sediment().delivered().equals(initial.sediment().delivered()))
            throw new IllegalArgumentException("Lake baseline world or delivery history changed");
        // A crash after receipt delivery may leave a proper subset of the
        // original plus verified sender receipts, but never a changed amount.
        for (var entry : current.sediment().receipts().entrySet())
            if (!Objects.equals(staged.receipts().get(entry.getKey()),entry.getValue()))
                throw new IllegalArgumentException("Unproven staged lake sediment receipt");
        if (!current.sediment().receipts().entrySet().containsAll(
                initial.sediment().receipts().entrySet()))
            throw new IllegalArgumentException("Original sediment receipt disappeared");
        return new Verified(expected,false);
    }
}
