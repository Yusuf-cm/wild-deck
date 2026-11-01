package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only, bounded loading plan for BOTH parked water and durable sediment.
 * The caller supplies authoritative sender journals and the exact spherical
 * sampling resolution. This does not consume receipts, advance an epoch, or
 * invent routes for unloaded terrain.
 *
 * All validation and the complete region budget run before the caller's
 * region provider is invoked. The resulting order is deterministic even when
 * sender and focus maps have different iteration orders.
 */
public final class HydrologyCoupledRegionLoadingV2 {
    private HydrologyCoupledRegionLoadingV2() {}

    private record Origin(long epoch, HydrologyGlobalCatchmentsV2.Coordinate source) {}

    public record Plan(List<HydrologyRegionSchedulerV2.Region> regions,
                       int pendingWaterReceipts, int pendingSedimentReceipts) {
        public Plan {
            regions = List.copyOf(Objects.requireNonNull(regions));
            if (pendingWaterReceipts < 0 || pendingSedimentReceipts < 0)
                throw new IllegalArgumentException("Invalid pending receipt count");
        }
    }

    private static boolean sameFile(Path left, Path right) throws IOException {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize()) ||
            Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
    }

    private static void compatible(GlobalHydrologyAddressV2.Sample sample,
                                   int level, int divisions) {
        if (sample.level() != level || sample.divisions() != divisions ||
            !HydrologySphericalTopologyV2.isCanonical(sample))
            throw new IllegalArgumentException("Pending receipt has incompatible spherical resolution");
    }

    private static HydrologyRegionSchedulerV2.Region region(
            GlobalHydrologyAddressV2.Sample sample) {
        long x = sample.x() == 0 ? 0 : (sample.x()-1)/sample.divisions();
        long y = sample.y() == 0 ? 0 : (sample.y()-1)/sample.divisions();
        return new HydrologyRegionSchedulerV2.Region(x,y);
    }

    /** Validate water and sediment before selecting any region or calling a provider. */
    public static Plan plan(
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            HydrologyEpochInboxV2.Snapshot water,
            Map<String,Path> sedimentSenders,
            Collection<GlobalHydrologyAddressV2.Sample> alreadyLoaded,
            int level,int divisions,int radius,int maximumRegions) throws IOException {
        Objects.requireNonNull(focus);
        Objects.requireNonNull(water);
        Objects.requireNonNull(sedimentSenders);
        Objects.requireNonNull(alreadyLoaded);
        if (level < 0 || level > 30 || divisions < 1 || divisions > 128)
            throw new IllegalArgumentException("Invalid spherical loading resolution");

        var selected = new TreeSet<HydrologyRegionSchedulerV2.Region>();
        for (var center : focus) selected.add(Objects.requireNonNull(center));

        var loaded = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var vertex : alreadyLoaded) {
            Objects.requireNonNull(vertex);
            if (!HydrologySphericalTopologyV2.isCanonical(vertex))
                throw new IllegalArgumentException("Noncanonical loaded vertex");
            if (!loaded.add(HydrologyGlobalCatchmentsV2.coordinate(vertex)))
                throw new IllegalArgumentException("Duplicate physical loaded vertex");
        }

        int waterCount = 0;
        for (var receipt : water.receipts().keySet()) {
            if (water.delivered().contains(receipt)) continue;
            compatible(receipt.from(),level,divisions);
            compatible(receipt.to(),level,divisions);
            selected.add(region(receipt.to()));
            waterCount = Math.addExact(waterCount,1);
        }

        var orderedSenders = new TreeMap<String,Path>();
        for (var entry : sedimentSenders.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid sediment sender key");
            orderedSenders.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        var paths = new ArrayList<Path>();
        var receipts = new HashSet<HydrologySedimentReceiptInboxV2.Receipt>();
        var origins = new HashMap<Origin,HydrologySedimentReceiptInboxV2.Receipt>();
        int sedimentCount = 0;
        for (var sender : orderedSenders.values()) {
            for (var prior : paths)
                if (sameFile(prior,sender))
                    throw new IllegalArgumentException("Duplicate sediment sender checkpoint");
            paths.add(sender);
            var snapshot = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(sender));
            for (var receipt : snapshot.receipts().keySet()) {
                if (!receipts.add(receipt))
                    throw new IllegalArgumentException("Duplicate sediment receipt across journals");
                var origin = new Origin(receipt.sourceEpoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(receipt.from()));
                if (origins.putIfAbsent(origin,receipt) != null)
                    throw new IllegalArgumentException("Forked physical sediment sender");
                if (snapshot.acknowledged().contains(receipt)) continue;
                // An already loaded physical destination does not need a load
                // hint, including when represented at another resolution.
                if (loaded.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.to())))
                    continue;
                compatible(receipt.from(),level,divisions);
                compatible(receipt.to(),level,divisions);
                selected.add(region(receipt.to()));
                sedimentCount = Math.addExact(sedimentCount,1);
            }
        }

        // This internal provider only records selected regions. The spherical
        // scheduler checks all focus coordinates, wrap/clamp and the full
        // expanded budget before any external provider is allowed to run.
        var expanded = new ArrayList<HydrologyRegionSchedulerV2.Region>();
        HydrologySphericalRegionSchedulerV2.load(
            selected,level,radius,maximumRegions,r -> {
                expanded.add(r);
                return List.of();
            });
        return new Plan(expanded,waterCount,sedimentCount);
    }

    /** Load only after complete read-only preflight; no receipt is consumed. */
    public static List<Collection<HydrologyFlowGraphV2.Node>> load(
            Collection<HydrologyRegionSchedulerV2.Region> focus,
            HydrologyEpochInboxV2.Snapshot water,
            Map<String,Path> sedimentSenders,
            Collection<GlobalHydrologyAddressV2.Sample> alreadyLoaded,
            int level,int divisions,int radius,int maximumRegions,
            HydrologyRegionSchedulerV2.Provider provider) throws IOException {
        Objects.requireNonNull(provider);
        var planned = plan(focus,water,sedimentSenders,alreadyLoaded,
            level,divisions,radius,maximumRegions);
        var result = new ArrayList<Collection<HydrologyFlowGraphV2.Node>>();
        for (var region : planned.regions())
            result.add(List.copyOf(Objects.requireNonNull(provider.load(region),
                "Provider returned null region")));
        return List.copyOf(result);
    }
}
