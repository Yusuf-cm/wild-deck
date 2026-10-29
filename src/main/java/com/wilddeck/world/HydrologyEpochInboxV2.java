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
                           Set<Long> consumedEpochs,
                           Set<HydrologyTransferLedgerV2.Key> delivered) {
        public Snapshot {
            receipts=Map.copyOf(receipts);
            consumedEpochs=Set.copyOf(consumedEpochs);
            delivered=Set.copyOf(delivered);
            if(!receipts.keySet().containsAll(delivered))
                throw new IllegalArgumentException("Delivered receipt missing from ledger");
        }
        /** Backwards-compatible snapshot: a claimed epoch delivered all its receipts. */
        public Snapshot(Map<HydrologyTransferLedgerV2.Key,Double> receipts,
                        Set<Long> consumedEpochs) {
            this(receipts,consumedEpochs,legacyDelivered(receipts,consumedEpochs));
        }
        private static Set<HydrologyTransferLedgerV2.Key> legacyDelivered(
                Map<HydrologyTransferLedgerV2.Key,Double> receipts,Set<Long> consumed) {
            var result=new HashSet<HydrologyTransferLedgerV2.Key>();
            for(var key:receipts.keySet())if(consumed.contains(key.epoch()))result.add(key);
            return result;
        }
    }

    private final HydrologyTransferLedgerV2 ledger;
    private final Set<Long> consumed=new HashSet<>();
    private final Set<HydrologyTransferLedgerV2.Key> delivered=new HashSet<>();

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
        for(var key:ledger.snapshot().keySet())
            if(key.epoch()==epoch)delivered.add(key);
        consumed.add(epoch);
        return incoming;
    }

    /**
     * Consume only receipts whose destination is currently loaded. Older
     * receipts retain their original edge/epoch identity until delivered,
     * preventing a retry or a later epoch from counting the same water twice.
     * Aggregation is validated before any receipt is marked delivered.
     */
    public synchronized Map<GlobalHydrologyAddressV2.Sample,Double> claimAvailable(
            long epoch,Set<GlobalHydrologyAddressV2.Sample> loaded) {
        if(epoch<0)throw new IllegalArgumentException("Invalid epoch");
        Objects.requireNonNull(loaded);
        if(consumed.contains(epoch))throw new IllegalStateException("Epoch already consumed");
        var totals=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var ready=new ArrayList<HydrologyTransferLedgerV2.Key>();
        for(var entry:ledger.snapshot().entrySet()){
            var key=entry.getKey();
            if(key.epoch()>epoch||delivered.contains(key)||!loaded.contains(key.to()))continue;
            double sum=totals.getOrDefault(key.to(),0.0)+entry.getValue();
            if(!Double.isFinite(sum))throw new IllegalArgumentException("Transfer overflow");
            totals.put(key.to(),sum);
            ready.add(key);
        }
        delivered.addAll(ready);
        consumed.add(epoch);
        return Collections.unmodifiableMap(totals);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(ledger.snapshot(),consumed,delivered);
    }

    public static HydrologyEpochInboxV2 restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var inbox=new HydrologyEpochInboxV2(
            HydrologyTransferLedgerV2.restore(snapshot.receipts()));
        for(long epoch:snapshot.consumedEpochs()) {
            if(epoch<0)throw new IllegalArgumentException("Invalid consumed epoch");
            inbox.consumed.add(epoch);
        }
        inbox.delivered.addAll(snapshot.delivered());
        return inbox;
    }
}
