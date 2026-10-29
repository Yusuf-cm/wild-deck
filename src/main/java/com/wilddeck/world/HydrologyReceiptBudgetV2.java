package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Deterministic audit of the durable incoming-water ledger.
 * Distinguishes water still parked at unloaded destinations from water
 * already delivered into a graph solve. Receipt identities remain in the
 * ledger for replay rejection; delivered volume must not be re-injected.
 *
 * This is receipt accounting only, not a global rainfall/ocean/lake budget.
 */
public final class HydrologyReceiptBudgetV2 {
    private HydrologyReceiptBudgetV2(){}

    public record Summary(Map<GlobalHydrologyAddressV2.Sample,Double> pendingByDestination,
                          double pendingVolume,double deliveredVolume,double totalVolume) {
        public Summary {
            var sorted=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            sorted.putAll(pendingByDestination);
            pendingByDestination=Collections.unmodifiableMap(sorted);
            if(!Double.isFinite(pendingVolume)||!Double.isFinite(deliveredVolume)||
               !Double.isFinite(totalVolume)||pendingVolume<0||
               deliveredVolume<0||totalVolume<0)
                throw new IllegalArgumentException("Invalid receipt budget");
        }
    }

    public static Summary summarize(HydrologyEpochInboxV2.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var pending=new TreeMap<GlobalHydrologyAddressV2.Sample,BigDecimal>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        BigDecimal waiting=BigDecimal.ZERO,delivered=BigDecimal.ZERO;
        for(var entry:snapshot.receipts().entrySet()){
            double volume=entry.getValue();
            if(!Double.isFinite(volume)||volume<0)
                throw new IllegalArgumentException("Invalid receipt water");
            var amount=BigDecimal.valueOf(volume);
            if(snapshot.delivered().contains(entry.getKey()))
                delivered=delivered.add(amount);
            else {
                waiting=waiting.add(amount);
                pending.merge(entry.getKey().to(),amount,BigDecimal::add);
            }
        }
        var destinations=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        pending.forEach((address,amount)->destinations.put(address,finite(amount)));
        return new Summary(destinations,finite(waiting),finite(delivered),
                           finite(waiting.add(delivered)));
    }

    private static double finite(BigDecimal value) {
        double converted=value.doubleValue();
        if(!Double.isFinite(converted))
            throw new IllegalArgumentException("Receipt volume overflow");
        return converted;
    }
}
