package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Deterministic load hints for unacknowledged durable sediment transfers.
 * Acknowledged receipts are NOT deleted from sender journals: they are
 * intentionally excluded from load hints while remaining available for
 * explicit disaster recovery.
 *
 * This is a bounded scheduling hint, not a proof of downstream routing or a
 * replacement for the authoritative region ownership directory. Mixed
 * destination resolutions fail closed because Region lacks a resolution tag.
 */
public final class HydrologySedimentRegionLoadHintsV2 {
    private HydrologySedimentRegionLoadHintsV2() {}

    private record Origin(long epoch,HydrologyGlobalCatchmentsV2.Coordinate at) {}

    public static List<HydrologyRegionSchedulerV2.Region> pendingRegions(
            Map<String,Path> senderFiles,
            Collection<GlobalHydrologyAddressV2.Sample> alreadyLoaded)
            throws IOException {
        Objects.requireNonNull(senderFiles);
        Objects.requireNonNull(alreadyLoaded);
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var vertex : alreadyLoaded) {
            Objects.requireNonNull(vertex);
            if (!HydrologySphericalTopologyV2.isCanonical(vertex))
                throw new IllegalArgumentException("Noncanonical loaded sediment vertex");
            owned.add(HydrologyGlobalCatchmentsV2.coordinate(vertex));
        }
        var senders = new TreeMap<String,Path>();
        for (var entry : senderFiles.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid sediment sender key");
            senders.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        var origins = new HashMap<Origin,HydrologySedimentReceiptInboxV2.Receipt>();
        var receipts = new HashSet<HydrologySedimentReceiptInboxV2.Receipt>();
        var selected = new TreeSet<HydrologyRegionSchedulerV2.Region>();
        GlobalHydrologyAddressV2.Sample resolution = null;
        for (var sender : senders.values()) {
            var snapshot = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(sender));
            for (var receipt : snapshot.receipts().keySet()) {
                if (!receipts.add(receipt))
                    throw new IllegalArgumentException("Duplicate sediment receipt across journals");
                var origin = new Origin(receipt.sourceEpoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(receipt.from()));
                if (origins.putIfAbsent(origin,receipt) != null)
                    throw new IllegalArgumentException("Forked physical sediment sender");
                if (snapshot.acknowledged().contains(receipt) ||
                    owned.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.to())))
                    continue;
                var to = receipt.to();
                if (resolution == null) resolution = to;
                else if (resolution.level() != to.level() ||
                         resolution.divisions() != to.divisions())
                    throw new IllegalArgumentException(
                        "Mixed sediment destination resolutions need a global region directory");
                long column = to.x()==0 ? 0 : (to.x()-1)/to.divisions();
                long row = to.y()==0 ? 0 : (to.y()-1)/to.divisions();
                selected.add(new HydrologyRegionSchedulerV2.Region(column,row));
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Combine explicit focus with unloaded sediment destinations, then use
     * the existing bounded region scheduler. A failed budget or resolution
     * check invokes no provider.
     */
    public static List<Collection<HydrologyFlowGraphV2.Node>> loadWithPending(
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            Map<String,Path> senderFiles,
            Collection<GlobalHydrologyAddressV2.Sample> alreadyLoaded,
            int radius,int maximumRegions,HydrologyRegionSchedulerV2.Provider provider)
            throws IOException {
        Objects.requireNonNull(focus);
        Objects.requireNonNull(provider);
        var regions = new TreeSet<HydrologyRegionSchedulerV2.Region>();
        for (var region : focus) regions.add(Objects.requireNonNull(region));
        regions.addAll(pendingRegions(senderFiles,alreadyLoaded));
        return HydrologyRegionSchedulerV2.load(regions,radius,maximumRegions,provider);
    }
}
