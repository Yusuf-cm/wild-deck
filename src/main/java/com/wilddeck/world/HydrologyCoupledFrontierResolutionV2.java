package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in bounded resolution of unloaded water/sediment destinations against
 * an authoritative physical-region directory. The directory supplies EXISTING
 * durable checkpoints and physical owners; no terrain, route or LOD is guessed.
 *
 * Directory lookups must be read-only and stable while writers are frozen.
 * Partial batches are independently durable, not a global transaction; retain
 * sender histories to recover safely from interrupted delivery.
 */
public final class HydrologyCoupledFrontierResolutionV2 {
    private HydrologyCoupledFrontierResolutionV2() {}

    @FunctionalInterface
    public interface Directory {
        Entry resolve(HydrologyGlobalCatchmentsV2.Coordinate coordinate) throws IOException;
    }

    public record Entry(String name, HydrologyCoupledLoadedTransfersV2.Region region) {
        public Entry {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("Invalid authoritative region name");
            Objects.requireNonNull(region);
        }
    }

    public record Plan(Map<String,HydrologyCoupledLoadedTransfersV2.Region> loaded,
            List<String> addedRegions,
            List<HydrologyCoupledLoadedTransfersV2.UnloadedFrontier> deferred,
            int resolvedFrontiers) {
        public Plan {
            loaded = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(loaded)));
            addedRegions = List.copyOf(Objects.requireNonNull(addedRegions));
            deferred = List.copyOf(Objects.requireNonNull(deferred));
            if (resolvedFrontiers < 0)
                throw new IllegalArgumentException("Invalid resolved frontier count");
        }
        public boolean complete() { return deferred.isEmpty(); }
    }

    public record Result(Plan plan, HydrologyCoupledLoadedTransfersV2.Result delivery) {
        public Result {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(delivery);
        }
    }

    private static TreeMap<String,HydrologyCoupledLoadedTransfersV2.Region> ordered(
            Map<String,HydrologyCoupledLoadedTransfersV2.Region> loaded) {
        Objects.requireNonNull(loaded);
        var result = new TreeMap<String,HydrologyCoupledLoadedTransfersV2.Region>();
        for (var entry : loaded.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid loaded region name");
            result.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        return result;
    }

    /**
     * Resolve every pending destination before selecting a deterministic,
     * bounded prefix of regions in frontier order. One region may own several
     * destinations and uses one budget slot. Ambiguous physical ownership is
     * rejected even when the conflicting region would be deferred.
     */
    public static Plan planBatch(Map<String,Path> waterSenders,
            Map<String,Path> sedimentSenders,
            Map<String,HydrologyCoupledLoadedTransfersV2.Region> alreadyLoaded,
            Directory directory,int maximumNewRegions) throws IOException {
        Objects.requireNonNull(directory);
        if (maximumNewRegions < 0)
            throw new IllegalArgumentException("Negative region loading budget");
        var initial = ordered(alreadyLoaded);
        var frontiers = HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            waterSenders,sedimentSenders,initial);
        var candidates = new LinkedHashMap<String,HydrologyCoupledLoadedTransfersV2.Region>();
        var physicalOwners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,String>();
        for (var region : initial.entrySet())
            claim(region.getKey(),region.getValue(),physicalOwners);

        for (var frontier : frontiers) {
            var entry = Objects.requireNonNull(directory.resolve(frontier.coordinate()),
                "No authoritative region for pending physical frontier");
            boolean ownsDestination = false;
            for (var sample : entry.region().ownedVertices()) {
                if (!HydrologySphericalTopologyV2.isCanonical(sample))
                    throw new IllegalArgumentException("Noncanonical directory vertex");
                if (HydrologyGlobalCatchmentsV2.coordinate(sample).equals(frontier.coordinate()))
                    ownsDestination = true;
            }
            if (!ownsDestination)
                throw new IllegalArgumentException("Directory region does not own destination");
            var existing = initial.get(entry.name());
            if (existing != null && !existing.equals(entry.region()))
                throw new IllegalArgumentException("Directory conflicts with loaded region");
            var previous = candidates.putIfAbsent(entry.name(),entry.region());
            if (previous != null && !previous.equals(entry.region()))
                throw new IllegalArgumentException("Directory changed region identity");
            if (previous == null && existing == null)
                claim(entry.name(),entry.region(),physicalOwners);
        }

        var chosen = new ArrayList<String>();
        for (var entry : candidates.entrySet()) {
            if (initial.containsKey(entry.getKey())) continue;
            if (chosen.size() == maximumNewRegions) break;
            chosen.add(entry.getKey());
            initial.put(entry.getKey(),entry.getValue());
        }
        // Read-only preflight of both durable formats before any receipt write.
        HydrologyCoupledLoadedTransfersV2.plan(waterSenders,sedimentSenders,initial);
        var selectedVertices = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var region : initial.values())
            for (var sample : region.ownedVertices())
                selectedVertices.add(HydrologyGlobalCatchmentsV2.coordinate(sample));
        var deferred = new ArrayList<HydrologyCoupledLoadedTransfersV2.UnloadedFrontier>();
        int resolved = 0;
        for (var frontier : frontiers) {
            if (selectedVertices.contains(frontier.coordinate())) resolved++;
            else deferred.add(frontier);
        }
        return new Plan(initial,chosen,deferred,resolved);
    }

    private static void claim(String name,HydrologyCoupledLoadedTransfersV2.Region region,
            Map<HydrologyGlobalCatchmentsV2.Coordinate,String> owners) {
        for (var sample : region.ownedVertices()) {
            if (!HydrologySphericalTopologyV2.isCanonical(sample))
                throw new IllegalArgumentException("Noncanonical physical owner");
            var previous = owners.putIfAbsent(HydrologyGlobalCatchmentsV2.coordinate(sample),name);
            if (previous != null)
                throw new IllegalArgumentException("Ambiguous physical region directory ownership");
        }
    }

    /** Fail closed unless every pending physical destination fits the budget. */
    public static Plan planAll(Map<String,Path> waterSenders,
            Map<String,Path> sedimentSenders,
            Map<String,HydrologyCoupledLoadedTransfersV2.Region> alreadyLoaded,
            Directory directory,int maximumNewRegions) throws IOException {
        var plan = planBatch(waterSenders,sedimentSenders,alreadyLoaded,
            directory,maximumNewRegions);
        if (!plan.complete())
            throw new IllegalStateException("Unresolved hydrology frontier exceeds region budget");
        return plan;
    }

    /** Deliver a bounded batch, leaving other durable receipts parked. */
    public static Result deliverBatch(Map<String,Path> waterSenders,
            Map<String,Path> sedimentSenders,
            Map<String,HydrologyCoupledLoadedTransfersV2.Region> alreadyLoaded,
            Directory directory,int maximumNewRegions) throws IOException {
        var plan = planBatch(waterSenders,sedimentSenders,alreadyLoaded,
            directory,maximumNewRegions);
        return new Result(plan,HydrologyCoupledLoadedTransfersV2.deliver(
            waterSenders,sedimentSenders,plan.loaded()));
    }

    /** Deliver only when the entire pending frontier fits the budget. */
    public static Result deliverAll(Map<String,Path> waterSenders,
            Map<String,Path> sedimentSenders,
            Map<String,HydrologyCoupledLoadedTransfersV2.Region> alreadyLoaded,
            Directory directory,int maximumNewRegions) throws IOException {
        var plan = planAll(waterSenders,sedimentSenders,alreadyLoaded,
            directory,maximumNewRegions);
        return new Result(plan,HydrologyCoupledLoadedTransfersV2.deliver(
            waterSenders,sedimentSenders,plan.loaded()));
    }

    /**
     * Resolve and preflight the ENTIRE pending frontier before the first write,
     * then drain it in deterministic bounded batches. A bad directory entry,
     * physical ownership overlap or insufficient total loading capacity fails
     * without acknowledging any receipt. The immutable preflight directory is
     * reused for every batch so a changing lookup cannot silently redirect it.
     *
     * Each water/sediment checkpoint is still committed independently. This is
     * NOT a globally atomic epoch: callers must freeze writers, retain sender
     * histories and recover by retrying with the original region inventory.
     */
    public record Drain(Plan preflight, List<Result> batches,
            Map<String,HydrologyCoupledLoadedTransfersV2.Region> loaded) {
        public Drain {
            Objects.requireNonNull(preflight);
            batches = List.copyOf(Objects.requireNonNull(batches));
            loaded = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(loaded)));
            if (batches.isEmpty() || !batches.get(batches.size()-1).plan().complete())
                throw new IllegalArgumentException("Incomplete frontier drain");
        }
    }

    public static Drain deliverBatches(Map<String,Path> waterSenders,
            Map<String,Path> sedimentSenders,
            Map<String,HydrologyCoupledLoadedTransfersV2.Region> alreadyLoaded,
            Directory directory,int maximumNewRegionsPerBatch,int maximumBatches)
            throws IOException {
        if (maximumNewRegionsPerBatch < 0 || maximumBatches <= 0)
            throw new IllegalArgumentException("Invalid frontier batch capacity");
        int capacity = Math.multiplyExact(maximumNewRegionsPerBatch,maximumBatches);
        // Resolve and validate all future receivers before any sender is changed.
        var preflight = planAll(waterSenders,sedimentSenders,alreadyLoaded,
            directory,capacity);
        var frozen = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,Entry>();
        for (var region : preflight.loaded().entrySet()) {
            for (var sample : region.getValue().ownedVertices()) {
                var coordinate = HydrologyGlobalCatchmentsV2.coordinate(sample);
                if (frozen.putIfAbsent(coordinate,
                        new Entry(region.getKey(),region.getValue())) != null)
                    throw new IllegalArgumentException("Duplicate physical frontier owner");
            }
        }
        Directory snapshot = coordinate -> {
            var owner = frozen.get(coordinate);
            if (owner == null)
                throw new IllegalStateException("Frontier changed after preflight");
            return owner;
        };
        var loaded = ordered(alreadyLoaded);
        var batches = new ArrayList<Result>();
        for (int index=0; index<maximumBatches; index++) {
            var batch = deliverBatch(waterSenders,sedimentSenders,loaded,
                snapshot,maximumNewRegionsPerBatch);
            batches.add(batch);
            loaded = ordered(batch.plan().loaded());
            if (batch.plan().complete()) {
                // Independently recheck durable receipts after the last batch.
                if (!HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
                        waterSenders,sedimentSenders,loaded).isEmpty())
                    throw new IOException("Unsettled frontier after completed drain");
                return new Drain(preflight,batches,loaded);
            }
            if (batch.plan().addedRegions().isEmpty())
                throw new IOException("Frontier drain made no loading progress");
        }
        throw new IOException("Frontier changed during bounded delivery");
    }
}
