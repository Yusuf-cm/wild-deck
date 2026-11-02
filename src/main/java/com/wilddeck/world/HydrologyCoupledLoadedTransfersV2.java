package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in single-writer orchestration of durable water and sediment arrivals
 * for the same loaded physical vertices. Both media are preflighted before
 * either writes. Retrying with unchanged sender histories is replay-safe.
 *
 * This is NOT a distributed atomic transaction, region loader, lake/river
 * epoch scheduler, or authorization to change drainage topology.
 */
public final class HydrologyCoupledLoadedTransfersV2 {
    private HydrologyCoupledLoadedTransfersV2() {}

    public record Region(Path waterCheckpoint, Path sedimentCheckpoint,
            List<GlobalHydrologyAddressV2.Sample> ownedVertices) {
        public Region {
            Objects.requireNonNull(waterCheckpoint);
            Objects.requireNonNull(sedimentCheckpoint);
            ownedVertices = List.copyOf(Objects.requireNonNull(ownedVertices));
        }
    }

    public record Plan(HydrologyMixedResolutionLoadedNetworkV2.Plan water,
            HydrologySedimentLoadedNetworkV2.Plan sediment,
            int loadedRegions, int physicalVertices) {
        public Plan {
            Objects.requireNonNull(water);
            Objects.requireNonNull(sediment);
            if (loadedRegions < 0 || physicalVertices < 0)
                throw new IllegalArgumentException("Invalid coupled network coverage");
        }
        /** Delivery settled does not mean sediment consumed or water routed. */
        public boolean loadedTransfersSettled() {
            return water.report().loadedSettled() &&
                sediment.pendingReceipts() == 0 &&
                sediment.pendingAcknowledgments() == 0;
        }
    }

    public record Result(Plan before,
            HydrologyMixedResolutionLoadedNetworkV2.Result water,
            HydrologySedimentLoadedNetworkV2.Result sediment,
            Plan after) {
        public Result {
            Objects.requireNonNull(before);
            Objects.requireNonNull(water);
            Objects.requireNonNull(sediment);
            Objects.requireNonNull(after);
            if (!after.loadedTransfersSettled())
                throw new IllegalArgumentException("Coupled loaded transfer remains unsettled");
        }
    }

    private static boolean sameFile(Path left, Path right) throws IOException {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize()) ||
            Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
    }

    private static <T> TreeMap<String,T> ordered(Map<String,T> entries) {
        Objects.requireNonNull(entries);
        var ordered = new TreeMap<String,T>();
        for (var entry : entries.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid coupled network region name");
            ordered.put(entry.getKey(), Objects.requireNonNull(entry.getValue()));
        }
        return ordered;
    }

    private record Inventories(
            TreeMap<String,Path> waterSenders,
            TreeMap<String,Path> sedimentSenders,
            TreeMap<String,HydrologyMixedResolutionLoadedNetworkV2.Receiver> water,
            TreeMap<String,HydrologySedimentLoadedRegionsV2.Receiver> sediment,
            int physicalVertices) {}

    private static Inventories validate(Map<String,Path> waterSenderFiles,
            Map<String,Path> sedimentSenderFiles,
            Map<String,Region> loaded) throws IOException {
        var waterSenders = ordered(waterSenderFiles);
        var sedimentSenders = ordered(sedimentSenderFiles);
        var regions = ordered(loaded);
        var water = new TreeMap<String,HydrologyMixedResolutionLoadedNetworkV2.Receiver>();
        var sediment = new TreeMap<String,HydrologySedimentLoadedRegionsV2.Receiver>();
        var claimed = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var waterPaths = new ArrayList<Path>();
        var sedimentPaths = new ArrayList<Path>();
        for (var entry : regions.entrySet()) {
            var region = entry.getValue();
            var local = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
            for (var sample : region.ownedVertices()) {
                if (!HydrologySphericalTopologyV2.isCanonical(sample))
                    throw new IllegalArgumentException("Noncanonical coupled receiver vertex");
                var coordinate = HydrologyGlobalCatchmentsV2.coordinate(sample);
                if (!local.add(coordinate) || !claimed.add(coordinate))
                    throw new IllegalArgumentException("Duplicate physical coupled receiver vertex");
            }
            water.put(entry.getKey(), new HydrologyMixedResolutionLoadedNetworkV2.Receiver(
                region.waterCheckpoint(),region.ownedVertices()));
            sediment.put(entry.getKey(), new HydrologySedimentLoadedRegionsV2.Receiver(
                region.sedimentCheckpoint(),region.ownedVertices()));
            waterPaths.add(region.waterCheckpoint());
            sedimentPaths.add(region.sedimentCheckpoint());
        }
        // Incompatible checkpoint formats cannot share a path, even through
        // a hard link or a differently normalized filesystem alias.
        waterPaths.addAll(waterSenders.values());
        sedimentPaths.addAll(sedimentSenders.values());
        for (var waterPath : waterPaths)
            for (var sedimentPath : sedimentPaths)
                if (sameFile(waterPath, sedimentPath))
                    throw new IllegalArgumentException("Water/sediment checkpoint path alias");
        return new Inventories(waterSenders,sedimentSenders,water,sediment,claimed.size());
    }

    /** Read-only preflight of both durable media before any receipt is written. */
    public static Plan plan(Map<String,Path> waterSenderFiles,
            Map<String,Path> sedimentSenderFiles,
            Map<String,Region> loaded) throws IOException {
        var inventories = validate(waterSenderFiles,sedimentSenderFiles,loaded);
        var water = HydrologyMixedResolutionLoadedNetworkV2.plan(
            inventories.waterSenders(),inventories.water());
        var sediment = HydrologySedimentLoadedNetworkV2.plan(
            inventories.sedimentSenders(),inventories.sediment());
        return new Plan(water,sediment,inventories.water().size(),
            inventories.physicalVertices());
    }

    /**
     * Persist water then sediment receipts in deterministic network order.
     * If interrupted, retry with complete histories; do not prune them.
     */
    public static Result deliver(Map<String,Path> waterSenderFiles,
            Map<String,Path> sedimentSenderFiles,
            Map<String,Region> loaded) throws IOException {
        var inventories = validate(waterSenderFiles,sedimentSenderFiles,loaded);
        var before = plan(waterSenderFiles,sedimentSenderFiles,loaded);
        var water = HydrologyMixedResolutionLoadedNetworkV2.deliver(
            inventories.waterSenders(),inventories.water());
        var sediment = HydrologySedimentLoadedNetworkV2.deliver(
            inventories.sedimentSenders(),inventories.sediment());
        var after = plan(waterSenderFiles,sedimentSenderFiles,loaded);
        if (!after.loadedTransfersSettled())
            throw new IOException("Coupled loaded delivery did not settle");
        return new Result(before,water,sediment,after);
    }
    /**
     * Deterministic physical destinations of still-unacknowledged water and
     * sediment for unloaded vertices. Cross-LOD aliases share one hint, but
     * amounts remain in separate units. A caller must resolve the coordinate
     * through its authoritative region directory; this does NOT guess an LOD,
     * discard already acknowledged history, or load a tile.
     */
    public record UnloadedFrontier(HydrologyGlobalCatchmentsV2.Coordinate coordinate,
            BigDecimal waterVolume, BigDecimal sedimentMass,
            int waterReceipts, int sedimentReceipts) {
        public UnloadedFrontier {
            Objects.requireNonNull(coordinate);
            Objects.requireNonNull(waterVolume);
            Objects.requireNonNull(sedimentMass);
            if (waterVolume.signum() < 0 || sedimentMass.signum() < 0 ||
                waterReceipts < 0 || sedimentReceipts < 0)
                throw new IllegalArgumentException("Invalid unloaded frontier budget");
        }
    }

    private static final class PendingFrontier {
        final HydrologyGlobalCatchmentsV2.Coordinate coordinate;
        BigDecimal water = BigDecimal.ZERO, sediment = BigDecimal.ZERO;
        int waterCount, sedimentCount;
        PendingFrontier(HydrologyGlobalCatchmentsV2.Coordinate coordinate) {
            this.coordinate = coordinate;
        }
        UnloadedFrontier snapshot() {
            return new UnloadedFrontier(coordinate,water,sediment,
                waterCount,sedimentCount);
        }
    }

    public static List<UnloadedFrontier> pendingUnloaded(
            Map<String,Path> waterSenderFiles,
            Map<String,Path> sedimentSenderFiles,
            Map<String,Region> loaded) throws IOException {
        var inventories = validate(waterSenderFiles,sedimentSenderFiles,loaded);
        // Fail closed on forked sources, missing provenance and aliases before
        // generating hints from durable sender histories.
        plan(waterSenderFiles,sedimentSenderFiles,loaded);
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var receiver : inventories.water().values())
            for (var vertex : receiver.ownedVertices())
                owned.add(HydrologyGlobalCatchmentsV2.coordinate(vertex));
        var pending = new TreeMap<String,PendingFrontier>();
        for (var path : inventories.waterSenders().values()) {
            var checkpoint = HydrologyMixedResolutionCheckpointStoreV2.load(path);
            for (var entry : checkpoint.outbox().entrySet()) {
                if (checkpoint.acknowledged().contains(entry.getKey())) continue;
                var coordinate = HydrologyGlobalCatchmentsV2.coordinate(entry.getKey().to());
                if (owned.contains(coordinate)) continue;
                var item = pending.computeIfAbsent(coordinate.stableKey(),
                    ignored -> new PendingFrontier(coordinate));
                item.water = item.water.add(BigDecimal.valueOf(entry.getValue()));
                item.waterCount = Math.addExact(item.waterCount,1);
            }
        }
        for (var path : inventories.sedimentSenders().values()) {
            var checkpoint = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(path));
            for (var entry : checkpoint.receipts().entrySet()) {
                if (checkpoint.acknowledged().contains(entry.getKey())) continue;
                var coordinate = HydrologyGlobalCatchmentsV2.coordinate(entry.getKey().to());
                if (owned.contains(coordinate)) continue;
                var item = pending.computeIfAbsent(coordinate.stableKey(),
                    ignored -> new PendingFrontier(coordinate));
                item.sediment = item.sediment.add(BigDecimal.valueOf(entry.getValue()));
                item.sedimentCount = Math.addExact(item.sedimentCount,1);
            }
        }
        var result = new ArrayList<UnloadedFrontier>();
        for (var item : pending.values()) result.add(item.snapshot());
        return List.copyOf(result);
    }

}
