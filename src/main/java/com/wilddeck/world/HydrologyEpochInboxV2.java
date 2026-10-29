package com.wilddeck.world;

import java.util.*;

/**
 * In-memory, epoch-scoped incoming water inbox. An epoch can be claimed only
 * once; its transfer receipts are retained for duplicate delivery detection.
 * Persist the complete snapshot atomically before acknowledging consumption.
 * This is not a distributed transactional store.
 */
public final class HydrologyEpochInboxV2 {
    public record Snapshot(Map<HydrologyTransferLedgerV2.Key,Double> receipts,
                           Set<Long> consumedEpochs) {
        public Snapshot {
            receipts=Map.copyOf(receipts);
            consumedEpochs=Set.copyOf(consumedEpochs);
        }
    }

    private final HydrologyTransferLedgerV2 ledger;
    private final Set<Long> consumed=new HashSet<>();

    public HydrologyEpochInboxV2() { this(new HydrologyTransferLedgerV2()); }
    private HydrologyEpochInboxV2(HydrologyTransferLedgerV2 ledger) {
        this.ledger=ledger;
    }

    public synchronized boolean receive(HydrologyTransferLedgerV2.Key key,double flow) {
        Objects.requireNonNull(key);
        if(consumed.contains(key.epoch()))
            throw new IllegalStateException("Cannot add water to consumed epoch");
        return ledger.accept(key,flow);
    }

    /**
     * Returns the epoch's accumulated incoming water once. A second claim
     * fails rather than reinjecting water into the next solve.
     */
    public synchronized Map<GlobalHydrologyAddressV2.Sample,Double> claim(long epoch) {
        if(epoch<0)throw new IllegalArgumentException("Invalid epoch");
        if(consumed.contains(epoch))
            throw new IllegalStateException("Epoch already consumed");
        // Validate/aggregate before marking consumed: overflow must not burn the epoch.
        var incoming=ledger.incoming(epoch);
        consumed.add(epoch);
        return incoming;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(ledger.snapshot(),consumed);
    }

    public static HydrologyEpochInboxV2 restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var inbox=new HydrologyEpochInboxV2(
            HydrologyTransferLedgerV2.restore(snapshot.receipts()));
        for(long epoch:snapshot.consumedEpochs()) {
            if(epoch<0)throw new IllegalArgumentException("Invalid consumed epoch");
            inbox.consumed.add(epoch);
        }
        return inbox;
    }
}
