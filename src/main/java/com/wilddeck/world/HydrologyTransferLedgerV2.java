package com.wilddeck.world;

import java.util.*;

/**
 * Idempotent boundary-transfer receipt ledger. A source edge may be accepted
 * once per simulation epoch; duplicate delivery with the same flow is ignored
 * rather than injecting the same water twice. Changed flow for an already
 * accepted edge is a conflict and requires a new epoch or explicit replay.
 *
 * This is an in-memory deterministic contract. Durable atomic writes and
 * concurrent distributed consumers are future runtime integration work.
 */
public final class HydrologyTransferLedgerV2 {
    public record Key(long epoch,GlobalHydrologyAddressV2.Sample from,
                       GlobalHydrologyAddressV2.Sample to) {
        public Key {
            Objects.requireNonNull(from);Objects.requireNonNull(to);
            if(epoch<0||from.level()!=to.level()||
               from.divisions()!=to.divisions()||from.equals(to)||
               Math.abs(from.x()-to.x())>1||Math.abs(from.y()-to.y())>1)
                throw new IllegalArgumentException("Invalid transfer key");
        }
    }

    private static final Comparator<Key> ORDER=Comparator.comparingLong(Key::epoch)
        .thenComparing(Key::from,HydrologyFlowGraphV2.ADDRESS_ORDER)
        .thenComparing(Key::to,HydrologyFlowGraphV2.ADDRESS_ORDER);

    private final TreeMap<Key,Double> received=new TreeMap<>(ORDER);

    public synchronized boolean accept(Key key,double flow) {
        Objects.requireNonNull(key);
        if(!Double.isFinite(flow)||flow<0)
            throw new IllegalArgumentException("Invalid transfer flow");
        var prior=received.get(key);
        if(prior!=null) {
            if(Double.compare(prior,flow)!=0)
                throw new IllegalArgumentException("Conflicting transfer replay");
            return false;
        }
        received.put(key,flow);
        return true;
    }

    public synchronized Map<GlobalHydrologyAddressV2.Sample,Double> incoming(long epoch) {
        if(epoch<0)throw new IllegalArgumentException("Invalid epoch");
        var totals=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var entry:received.entrySet()) {
            if(entry.getKey().epoch()!=epoch)continue;
            var destination=entry.getKey().to();
            double total=totals.getOrDefault(destination,0.0)+entry.getValue();
            if(!Double.isFinite(total))throw new IllegalArgumentException("Transfer overflow");
            totals.put(destination,total);
        }
        return Collections.unmodifiableMap(totals);
    }

    public synchronized Map<Key,Double> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(received));
    }

    public static HydrologyTransferLedgerV2 restore(Map<Key,Double> snapshot) {
        Objects.requireNonNull(snapshot);
        var ledger=new HydrologyTransferLedgerV2();
        for(var entry:snapshot.entrySet())ledger.accept(entry.getKey(),entry.getValue());
        return ledger;
    }
}
