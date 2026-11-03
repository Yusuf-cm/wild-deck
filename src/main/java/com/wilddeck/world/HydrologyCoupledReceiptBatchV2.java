package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Locally atomic ingress for water and sediment arriving together at a
 * receiver. Both receipt histories are staged before one checksummed,
 * compare-and-swap checkpoint replacement. Historical exact replays are
 * idempotent, including receipts from closed epochs; new late receipts fail.
 *
 * This does not authenticate remote sender debits, consume either receipt,
 * acknowledge a remote sender, or provide a distributed transaction.
 */
public final class HydrologyCoupledReceiptBatchV2 {
    private HydrologyCoupledReceiptBatchV2() {}

    public record Accepted(HydrologyCheckpointV2.Checkpoint checkpoint,
                           int waterAdded, int sedimentAdded) {
        public Accepted {
            Objects.requireNonNull(checkpoint);
            if (waterAdded < 0 || sedimentAdded < 0)
                throw new IllegalArgumentException("Negative accepted receipt count");
        }
    }

    public static Accepted receive(
            Path path, HydrologyCheckpointV2.Checkpoint expected,
            Map<HydrologyTransferLedgerV2.Key,Double> water,
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> sediment)
            throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(water);
        Objects.requireNonNull(sediment);
        var previousWorld = HydrologySedimentCheckpointV2.decode(expected.worldState());
        var oldWater = HydrologyInboxCodecV2.decode(expected.inboxState());

        // A previously accepted receipt may be replayed after its epoch has
        // closed, but an unseen transfer must never enter a committed epoch.
        // Validate historical replays before passing future keys to the
        // epoch inbox, which deliberately rejects all consumed epochs.
        var futureWater = new HashMap<HydrologyTransferLedgerV2.Key,Double>();
        for (var entry : water.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey());
            var amount = Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(amount) || amount < 0)
                throw new IllegalArgumentException("Invalid water receipt volume");
            var known = oldWater.receipts().get(key);
            if (known != null && Double.compare(known,amount) != 0)
                throw new IllegalArgumentException("Conflicting historical water replay");
            if (key.epoch() <= expected.epoch()) {
                if (known == null)
                    throw new IllegalArgumentException("New water receipt targets closed epoch");
            } else {
                futureWater.put(key,amount);
            }
        }
        for (var receipt : sediment.keySet()) {
            Objects.requireNonNull(receipt);
            if (!previousWorld.sediment().receipts().containsKey(receipt) &&
                receipt.arrivalEpoch() <= expected.epoch())
                throw new IllegalArgumentException("New sediment receipt targets closed epoch");
        }

        var stagedWater = HydrologyEpochInboxV2.restore(oldWater);
        int waterAdded = stagedWater.receiveBatch(futureWater);
        var stagedSediment = HydrologySedimentReceiptInboxV2.receiveBatch(
            previousWorld.sediment(),sediment);
        int sedimentAdded = stagedSediment.receipts().size()
            - previousWorld.sediment().receipts().size();
        var next = new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            HydrologySedimentCheckpointV2.encode(previousWorld.world(),stagedSediment),
            HydrologyInboxCodecV2.encode(stagedWater.snapshot()));
        // Even a no-op replay checks freshness under the same filesystem lock.
        HydrologyCheckpointStoreV2.saveCoupledIncoming(path,expected,next);
        return new Accepted(next,waterAdded,sedimentAdded);
    }
}
