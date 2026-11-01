package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Deterministic boundary-sediment receipt creation from a verified route
 * result. Unlike destination-aggregated pendingExport, each receipt preserves
 * the unique sending edge, so converging sources cannot lose provenance.
 * This is an outbox proposal, not a durable send or acknowledgment.
 */
public final class HydrologySedimentFrontierTransfersV2 {
    private HydrologySedimentFrontierTransfersV2() {}

    public record Outgoing(Map<HydrologySedimentReceiptInboxV2.Receipt,Double> receipts,
                           double pendingMass) {
        public Outgoing {
            Objects.requireNonNull(receipts);
            if (!Double.isFinite(pendingMass) || pendingMass < 0)
                throw new IllegalArgumentException("Invalid frontier sediment budget");
            receipts = Collections.unmodifiableMap(new LinkedHashMap<>(receipts));
        }
    }

    public static Outgoing prepare(HydrologySphericalLakeBasinsV2.Plan plan,
            HydrologyRiverSedimentTransportV2.Result routed,
            long sourceEpoch,long arrivalEpoch) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(routed);
        if (sourceEpoch < 0 || arrivalEpoch < sourceEpoch)
            throw new IllegalArgumentException("Invalid sediment frontier epoch");
        var nodes = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : plan.routes())
            if (nodes.putIfAbsent(node.address(),node) != null)
                throw new IllegalArgumentException("Duplicate sediment source ownership");
        if (!routed.transported().keySet().equals(nodes.keySet()))
            throw new IllegalArgumentException("Sediment routing source mismatch");
        var receipts = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        var totals = new TreeMap<GlobalHydrologyAddressV2.Sample,BigDecimal>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : nodes.values()) {
            var next = node.downstream();
            double mass = Objects.requireNonNull(routed.transported().get(node.address()));
            if (!Double.isFinite(mass) || mass < 0)
                throw new IllegalArgumentException("Invalid transported sediment");
            if (node.terminal() || next != null && nodes.containsKey(next)) continue;
            if (next == null) {
                if (mass != 0)
                    throw new IllegalArgumentException("Unresolved sediment frontier has no receiving edge");
                continue;
            }
            if (mass == 0) continue;
            var receipt = new HydrologySedimentReceiptInboxV2.Receipt(
                sourceEpoch,arrivalEpoch,node.address(),next);
            if (receipts.putIfAbsent(receipt,mass) != null)
                throw new IllegalArgumentException("Duplicate sediment transfer edge");
            totals.merge(next,BigDecimal.valueOf(mass),BigDecimal::add);
        }
        if (!totals.keySet().equals(routed.pendingExport().keySet())) {
            // Zero pending destinations are harmless but must not hide mass.
            for (var entry : routed.pendingExport().entrySet())
                if (entry.getValue() != 0 && !totals.containsKey(entry.getKey()))
                    throw new IllegalArgumentException("Unaccounted pending sediment destination");
            for (var entry : totals.entrySet())
                if (!routed.pendingExport().containsKey(entry.getKey()))
                    throw new IllegalArgumentException("Invented sediment frontier transfer");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : totals.entrySet()) {
            double expected = routed.pendingExport().getOrDefault(entry.getKey(),Double.NaN);
            if (!Double.isFinite(expected) ||
                Double.compare(entry.getValue().doubleValue(),expected) != 0)
                throw new IllegalArgumentException("Sediment frontier conservation mismatch");
            total = total.add(entry.getValue());
        }
        double pending = total.doubleValue();
        if (!Double.isFinite(pending) ||
            Double.compare(pending,routed.budget().pendingExported()) != 0)
            throw new IllegalArgumentException("Sediment frontier budget mismatch");
        return new Outgoing(receipts,pending);
    }
}
