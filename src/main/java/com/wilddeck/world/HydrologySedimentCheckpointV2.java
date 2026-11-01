package com.wilddeck.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Opt-in single-writer sediment checkpoint envelope. One checksummed world
 * payload contains the original lake/terrain state AND the sediment receipt
 * ledger, and the existing atomic checkpoint store persists it with the water
 * inbox. No external sender acknowledgment is implied.
 *
 * Only imported sediment that is fully captured by existing lake beds can
 * advance here. River erosion/deposition, sediment exported downstream and
 * topology changes require an independent verified terrain transaction.
 */
public final class HydrologySedimentCheckpointV2 {
    private HydrologySedimentCheckpointV2() {}
    private static final String HEADER = "WD-SEDIMENT-CHECKPOINT-1";
    private static final int MAX_BYTES = 24 * 1024 * 1024;

    public record State(String world,HydrologySedimentReceiptInboxV2.Snapshot sediment) {
        public State {
            Objects.requireNonNull(world);
            Objects.requireNonNull(sediment);
        }
    }

    public record Committed(HydrologyCheckpointV2.Checkpoint checkpoint,
                            HydrologySedimentReceiptInboxV2.Snapshot sediment,
                            HydrologyRiverLakeSedimentCouplingV2.ImportedResult result) {
        public Committed {
            Objects.requireNonNull(checkpoint);
            Objects.requireNonNull(sediment);
            Objects.requireNonNull(result);
        }
    }

    public static String encode(String world,HydrologySedimentReceiptInboxV2.Snapshot sediment) {
        Objects.requireNonNull(world);
        String ledger = HydrologySedimentReceiptCodecV2.encode(sediment);
        String body = HEADER + "\n" +
            Base64.getEncoder().encodeToString(world.getBytes(StandardCharsets.UTF_8)) +
            "\n" + Base64.getEncoder().encodeToString(
                ledger.getBytes(StandardCharsets.UTF_8)) + "\n";
        String encoded = body + digest(body) + "\n";
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Sediment checkpoint envelope too large");
        return encoded;
    }

    public static State decode(String encoded) {
        Objects.requireNonNull(encoded);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Sediment checkpoint envelope too large");
        String[] lines = encoded.split("\\n", -1);
        if (lines.length != 5 || !HEADER.equals(lines[0]) || !lines[4].isEmpty())
            throw new IllegalArgumentException("Invalid sediment checkpoint envelope");
        String body = lines[0] + "\n" + lines[1] + "\n" + lines[2] + "\n";
        if (!digest(body).equals(lines[3]))
            throw new IllegalArgumentException("Sediment checkpoint checksum mismatch");
        try {
            String world = new String(Base64.getDecoder().decode(lines[1]),
                StandardCharsets.UTF_8);
            String ledger = new String(Base64.getDecoder().decode(lines[2]),
                StandardCharsets.UTF_8);
            var result = new State(world,HydrologySedimentReceiptCodecV2.decode(ledger));
            if (!encode(result.world(),result.sediment()).equals(encoded))
                throw new IllegalArgumentException("Noncanonical sediment checkpoint envelope");
            return result;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed sediment checkpoint envelope",ex);
        }
    }

    /** Use only when constructing a NEW checkpoint, before its first save. */
    public static String initialWorld(String lakeSnapshot) {
        return encode(lakeSnapshot,HydrologySedimentReceiptInboxV2.empty());
    }

    /**
     * Append a future-arrival receipt to the current durable epoch. The store
     * checks the exact expected checkpoint and permits only monotonic receipt
     * additions; delivered markers, water inbox and world state cannot change.
     */
    /**
     * Atomically append a deterministic batch of future sediment arrivals.
     * Every receipt is validated and staged before the ONE checkpoint write;
     * an invalid late item, physical-source fork, stale writer, or I/O error
     * cannot persist an earlier subset. Exact replays remain idempotent.
     * This is single-writer local-file atomicity, not sender acknowledgment.
     */
    public static HydrologyCheckpointV2.Checkpoint receiveBatch(
            Path path, HydrologyCheckpointV2.Checkpoint expected,
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> incoming)
            throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(incoming);
        var before = decode(expected.worldState());
        var order = Comparator
            .comparingLong(HydrologySedimentReceiptInboxV2.Receipt::arrivalEpoch)
            .thenComparingLong(HydrologySedimentReceiptInboxV2.Receipt::sourceEpoch)
            .thenComparing(HydrologySedimentReceiptInboxV2.Receipt::from,
                HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(HydrologySedimentReceiptInboxV2.Receipt::to,
                HydrologyFlowGraphV2.ADDRESS_ORDER);
        var sorted = new TreeMap<HydrologySedimentReceiptInboxV2.Receipt,Double>(order);
        for (var entry : incoming.entrySet())
            sorted.put(Objects.requireNonNull(entry.getKey()),
                Objects.requireNonNull(entry.getValue()));
        var staged = before.sediment();
        for (var entry : sorted.entrySet()) {
            var receipt = entry.getKey();
            // Existing exact replays are legal even after the arrival epoch.
            if (!staged.receipts().containsKey(receipt) &&
                receipt.arrivalEpoch() <= expected.epoch())
                throw new IllegalArgumentException("Cannot append sediment to a committed epoch");
            staged = HydrologySedimentReceiptInboxV2.receive(
                staged,receipt,entry.getValue());
        }
        if (staged == before.sediment()) {
            if (!HydrologyCheckpointStoreV2.load(path).equals(expected))
                throw new IOException("Stale sediment batch writer");
            return expected;
        }
        var next = new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            encode(before.world(),staged),expected.inboxState());
        HydrologyCheckpointStoreV2.saveSedimentIncoming(path,expected,next);
        return next;
    }

    public static HydrologyCheckpointV2.Checkpoint receive(
            Path path,HydrologyCheckpointV2.Checkpoint expected,
            HydrologySedimentReceiptInboxV2.Receipt receipt,double mass)
            throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(expected);
        var before = decode(expected.worldState());
        var after = HydrologySedimentReceiptInboxV2.receive(
            before.sediment(),receipt,mass);
        if (after == before.sediment()) {
            if (!HydrologyCheckpointStoreV2.load(path).equals(expected))
                throw new IOException("Stale sediment receipt writer");
            return expected;
        }
        if (receipt.arrivalEpoch() <= expected.epoch())
            throw new IllegalArgumentException("Sediment receipt must arrive after committed epoch");
        var next = new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            encode(before.world(),after),expected.inboxState());
        HydrologyCheckpointStoreV2.saveSedimentIncoming(path,expected,next);
        return next;
    }

    /**
     * Advance an unchanged, zero-runoff verified lake network with imported
     * sediment. Requires all delivered mass to settle inside existing lakes.
     * No riverbed mutation, local erosion, exported sediment, pending water
     * receipts or new drainage route can be silently committed.
     */
    public static Committed advanceLake(
            Path path,HydrologyCheckpointV2.Checkpoint previous,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode)
            throws IOException {
        return advanceLakeInternal(path,previous,baseline,water,transportCapacity,
            dryBulkDensity,mode,null,null);
    }

    /**
     * Persist imported sediment, lake-bed elevation, lake geometry, retained
     * water and consumed sediment receipt markers in ONE guarded checkpoint.
     * The previous lake bed must match the authoritative terrain overlay
     * exactly. Existing lake ownership/spill topology cannot change.
     *
     * This does NOT commit riverbed changes or authenticate sender histories.
     * Delivery must first be persisted by the durable sediment coordinator.
     */
    public static Committed advanceLakeWithTerrain(
            Path path,HydrologyCheckpointV2.Checkpoint previous,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            String nextWorld,HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode)
            throws IOException {
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(seed);
        return advanceLakeInternal(path,previous,baseline,water,transportCapacity,
            dryBulkDensity,mode,nextWorld,seed);
    }

    private static Committed advanceLakeInternal(
            Path path,HydrologyCheckpointV2.Checkpoint previous,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            double dryBulkDensity,SeasonalLakeEvolutionV2.EvaporationMode mode,
            String nextWorld,HydrologyGlobalSlopeRouterV2.Elevations seed)
            throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(water);
        Objects.requireNonNull(transportCapacity);
        Objects.requireNonNull(mode);
        var current = decode(previous.worldState());
        if (current.sediment().lastClaimedEpoch() != previous.epoch() &&
            !(previous.epoch() == 0 && current.sediment().lastClaimedEpoch() == -1))
            throw new IllegalArgumentException("Sediment ledger epoch disagrees with checkpoint");
        var waterInbox = HydrologyInboxCodecV2.decode(previous.inboxState());
        if (!waterInbox.delivered().containsAll(waterInbox.receipts().keySet()))
            throw new IllegalArgumentException("Pending water receipts need a coupled water transaction");
        var eroded = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : baseline.routes()) {
            if (node.localWater() != 0 || eroded.putIfAbsent(node.address(),0.0) != null)
                throw new IllegalArgumentException("Baseline must have unique zero-runoff vertices");
        }
        var restored = HydrologyLakeSedimentSnapshotV2.decode(baseline,current.world());
        long epoch = Math.addExact(previous.epoch(),1);
        var staged = HydrologySedimentReceiptRoutingV2.stageLake(
            current.sediment(),epoch,restored.plan(),restored.water(),
            water,eroded,transportCapacity,dryBulkDensity,mode);
        var solved = staged.result().result();
        var river = solved.river();
        var lake = solved.lake();
        if (river.budget().riverDeposited() != 0 ||
            river.budget().terminalExported() != 0 ||
            river.budget().pendingExported() != 0 ||
            lake.budget().sedimentExported() != 0)
            throw new IllegalArgumentException(
                "Sediment leaves verified lake beds: terrain/export transaction required");
        String world = seed == null ? restored.water().world() :
            HydrologyLakeSedimentTerrainBindingV2.updateWorld(nextWorld,seed,
                restored.plan(),lake.updatedPlan(),restored.water().world());
        var retained = new HydrologyLakeStateV2.State(
            world,lake.water().state().volumes());
        String lakePayload = HydrologyLakeSedimentSnapshotV2.encode(
            baseline,lake.updatedPlan(),retained);
        var next = new HydrologyCheckpointV2.Checkpoint(epoch,
            encode(lakePayload,staged.snapshot()),previous.inboxState());
        HydrologyCheckpointStoreV2.saveNext(path,previous,next);
        return new Committed(next,staged.snapshot(),staged.result());
    }

    private static String digest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}
