package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Replay-resistant sediment receipt staging for one checkpoint writer.
 * A physical source may emit one boundary transfer per source epoch, even
 * when senders refer to it using different sampling resolutions. Arrivals
 * remain parked until their destination is loaded; old receipt identities
 * remain recorded after delivery to reject replay across restarts.
 *
 * This is not sender authentication or a distributed transaction. A caller
 * MUST atomically persist the snapshot with the resulting sediment/terrain
 * state before acknowledging any transfer. Never compact the receipt history
 * without an independently established global replay barrier.
 */
public final class HydrologySedimentReceiptInboxV2 {
    private HydrologySedimentReceiptInboxV2() {}

    public record Receipt(long sourceEpoch, long arrivalEpoch,
                          GlobalHydrologyAddressV2.Sample from,
                          GlobalHydrologyAddressV2.Sample to) {
        public Receipt {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (sourceEpoch < 0 || arrivalEpoch < sourceEpoch ||
                from.level() != to.level() ||
                from.divisions() != to.divisions() ||
                !HydrologySphericalTopologyV2.isCanonical(from) ||
                !HydrologySphericalTopologyV2.isCanonical(to) ||
                !HydrologySphericalTopologyV2.adjacent(from,to))
                throw new IllegalArgumentException("Invalid sediment transfer edge or epoch");
            // Geographic normalization also rejects seam/pole aliases.
            HydrologyGlobalCatchmentsV2.coordinate(from);
            HydrologyGlobalCatchmentsV2.coordinate(to);
        }
    }

    private static final Comparator<Receipt> ORDER =
        Comparator.comparingLong(Receipt::arrivalEpoch)
            .thenComparingLong(Receipt::sourceEpoch)
            .thenComparing(Receipt::from,HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(Receipt::to,HydrologyFlowGraphV2.ADDRESS_ORDER);

    private record Origin(long epoch,HydrologyGlobalCatchmentsV2.Coordinate position) {}

    public record Snapshot(Map<Receipt,Double> receipts,
                           Set<Receipt> delivered,long lastClaimedEpoch) {
        public Snapshot {
            Objects.requireNonNull(receipts);
            Objects.requireNonNull(delivered);
            if (lastClaimedEpoch < -1)
                throw new IllegalArgumentException("Invalid last claimed epoch");
            var ordered = new TreeMap<Receipt,Double>(ORDER);
            for (var entry : receipts.entrySet()) {
                Objects.requireNonNull(entry.getKey());
                checkMass(entry.getValue());
                ordered.put(entry.getKey(),entry.getValue());
            }
            if (!ordered.keySet().containsAll(delivered))
                throw new IllegalArgumentException("Delivered sediment receipt absent from ledger");
            for (var receipt : delivered)
                if (receipt.arrivalEpoch() > lastClaimedEpoch)
                    throw new IllegalArgumentException("Future receipt marked delivered");
            // Validate physical-source uniqueness even for forged snapshots.
            var origins = new HashMap<Origin,Receipt>();
            for (var receipt : ordered.keySet()) {
                var prior = origins.putIfAbsent(origin(receipt),receipt);
                if (prior != null && !prior.equals(receipt))
                    throw new IllegalArgumentException("Conflicting physical sediment sender");
            }
            receipts = Collections.unmodifiableMap(ordered);
            delivered = Set.copyOf(delivered);
        }
    }

    private static Origin origin(Receipt receipt) {
        return new Origin(receipt.sourceEpoch(),
            HydrologyGlobalCatchmentsV2.coordinate(receipt.from()));
    }

    private static BigDecimal checkMass(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid sediment mass");
        return BigDecimal.valueOf(value);
    }

    public static Snapshot empty() { return new Snapshot(Map.of(),Set.of(),-1); }

    /** Duplicate exact receipts are no-ops; changed amounts or forks fail. */
    public static Snapshot receive(Snapshot previous,Receipt receipt,double mass) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(receipt);
        checkMass(mass);
        Double existing = previous.receipts().get(receipt);
        if (existing != null) {
            if (Double.compare(existing,mass) != 0)
                throw new IllegalArgumentException("Conflicting sediment receipt replay");
            return previous;
        }
        if (receipt.arrivalEpoch() <= previous.lastClaimedEpoch())
            throw new IllegalStateException("New sediment receipt arrives after its epoch closed");
        for (var known : previous.receipts().keySet())
            if (origin(known).equals(origin(receipt)))
                throw new IllegalArgumentException("Physical sediment sender fork or alias replay");
        var next = new TreeMap<Receipt,Double>(ORDER);
        next.putAll(previous.receipts());
        next.put(receipt,mass);
        return new Snapshot(next,previous.delivered(),previous.lastClaimedEpoch());
    }

    public record Claimed(Snapshot snapshot,
                          Map<GlobalHydrologyAddressV2.Sample,Double> imported,
                          double importedMass) {
        public Claimed {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(imported);
            if (!Double.isFinite(importedMass) || importedMass < 0)
                throw new IllegalArgumentException("Invalid imported mass budget");
            imported = Map.copyOf(imported);
        }
    }

    /**
     * Claim eligible arrivals only for loaded physical vertices. Unloaded
     * destinations remain pending across epochs; all updates are staged and
     * can be discarded if the downstream solve or checkpoint fails.
     */
    public static Claimed claimAvailable(Snapshot previous,long epoch,
            Collection<GlobalHydrologyAddressV2.Sample> loaded) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(loaded);
        if (epoch < 0 || epoch <= previous.lastClaimedEpoch())
            throw new IllegalStateException("Sediment epoch already closed");
        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var address : loaded) {
            Objects.requireNonNull(address);
            var location = HydrologyGlobalCatchmentsV2.coordinate(address);
            if (owners.putIfAbsent(location,address) != null)
                throw new IllegalArgumentException("Duplicate geographic sediment receiver ownership");
        }
        var totals = new TreeMap<GlobalHydrologyAddressV2.Sample,BigDecimal>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var delivered = new HashSet<>(previous.delivered());
        // Audit representability before exact-decimal totals are narrowed to
        // double-valued erosion inputs. No positive arrival may vanish.
        var represented = new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        double representedTotal = 0;
        BigDecimal imported = BigDecimal.ZERO;
        for (var entry : previous.receipts().entrySet()) {
            var receipt = entry.getKey();
            if (receipt.arrivalEpoch() > epoch || delivered.contains(receipt)) continue;
            var owner = owners.get(HydrologyGlobalCatchmentsV2.coordinate(receipt.to()));
            if (owner == null) continue;
            BigDecimal mass = checkMass(entry.getValue());
            totals.merge(owner,mass,BigDecimal::add);
            represented.put(owner, SedimentMassAdditionV2.add(
                represented.getOrDefault(owner,0.0), entry.getValue()));
            representedTotal = SedimentMassAdditionV2.add(
                representedTotal,entry.getValue());
            imported = imported.add(mass);
            delivered.add(receipt);
        }
        var flux = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : totals.entrySet()) {
            double amount = entry.getValue().doubleValue();
            if (!Double.isFinite(amount))
                throw new IllegalArgumentException("Sediment receipt aggregation overflow");
            flux.put(entry.getKey(),amount);
        }
        double mass = imported.doubleValue();
        if (!Double.isFinite(mass))
            throw new IllegalArgumentException("Sediment mass budget overflow");
        return new Claimed(new Snapshot(previous.receipts(),delivered,epoch),flux,mass);
    }
}
