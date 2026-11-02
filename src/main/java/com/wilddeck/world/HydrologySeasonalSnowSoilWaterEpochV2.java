package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in, single-checkpoint seasonal transition for audited spherical snow/soil
 * stores and topology-bound lake/frontier/ocean water. Precipitation enters the
 * drainage graph ONLY as snow/soil runoff, never as a second rainfall source.
 *
 * All persisted snow/soil cells must be loaded and forced each season. This
 * intentionally fails closed on partial snow/soil streaming, topology changes,
 * changed planet radius, and unknown physical cell ownership. It is not a
 * distributed transaction or a climate/terrain/sediment evolution engine.
 */
public final class HydrologySeasonalSnowSoilWaterEpochV2 {
    private HydrologySeasonalSnowSoilWaterEpochV2() {}
    private static final String MAGIC = "WD-SNOW-TOPO-WATER-1";
    private static final int MAX_PAYLOAD = 48 * 1024 * 1024;

    public record State(double radiusMeters, String topologyState, String snowState) {
        public State {
            if (!Double.isFinite(radiusMeters) || radiusMeters <= 0)
                throw new IllegalArgumentException("Invalid planetary radius");
            Objects.requireNonNull(topologyState);
            Objects.requireNonNull(snowState);
            var topology = HydrologySeasonalTopologyEpochV2.decode(topologyState);
            var water = HydrologySeasonalOutletSettlementV2.decode(topology.waterState());
            var snow = HydrologySnowSoilStateV2.decode(snowState);
            if (!water.lakes().world().equals(snow.world()))
                throw new IllegalArgumentException("Seasonal world identities differ");
            uniquePhysical(snow.cells().keySet());
        }
    }

    public record Prepared(HydrologySnowSoilStateV2.Step snow,
            HydrologySeasonalTopologyEpochV2.Prepared water,
            HydrologyCheckpointV2.Checkpoint checkpoint) {
        public Prepared {
            Objects.requireNonNull(snow);
            Objects.requireNonNull(water);
            Objects.requireNonNull(checkpoint);
        }
    }

    /**
     * Upgrade an empty, already topology-bound checkpoint before its first
     * seasonal epoch. Nonempty initial snow/soil storage is allowed because
     * it is explicitly persisted in the same initial checkpoint.
     */
    public static HydrologyCheckpointV2.Checkpoint bindFresh(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySnowSoilStateV2.State snow, double radiusMeters) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(snow);
        var bound = HydrologySeasonalTopologyEpochV2.decode(expected.worldState());
        var water = HydrologySeasonalOutletSettlementV2.decode(bound.waterState());
        var lake = water.lakes();
        var inbox = HydrologyCheckpointV2.restoreInbox(expected).snapshot();
        if (expected.epoch() != 0 || lake.season() != 0 ||
            !lake.pendingM3().isEmpty() || !water.dischargedM3().isEmpty() ||
            lake.lakeStorageM3().values().stream().anyMatch(v -> v != 0.0) ||
            !inbox.receipts().isEmpty() || !inbox.delivered().isEmpty() ||
            !inbox.consumedEpochs().isEmpty())
            throw new IllegalArgumentException("Cannot bind in-flight water or receipts");
        return new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            encode(new State(radiusMeters,expected.worldState(),
                HydrologySnowSoilStateV2.encode(snow))),expected.inboxState());
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        String value = MAGIC + "\n" + Double.toHexString(state.radiusMeters()) + "\n" +
            Base64.getEncoder().encodeToString(
                state.topologyState().getBytes(StandardCharsets.UTF_8)) + "\n" +
            Base64.getEncoder().encodeToString(
                state.snowState().getBytes(StandardCharsets.UTF_8)) + "\n";
        if (value.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Combined seasonal checkpoint too large");
        return value;
    }

    public static State decode(String value) {
        Objects.requireNonNull(value);
        if (value.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Combined seasonal checkpoint too large");
        var lines = value.split("\\n",-1);
        if (lines.length != 5 || !MAGIC.equals(lines[0]) || !lines[4].isEmpty())
            throw new IllegalArgumentException("Invalid combined seasonal checkpoint");
        try {
            var state = new State(Double.parseDouble(lines[1]),
                new String(Base64.getDecoder().decode(lines[2]),StandardCharsets.UTF_8),
                new String(Base64.getDecoder().decode(lines[3]),StandardCharsets.UTF_8));
            if (!encode(state).equals(value))
                throw new IllegalArgumentException("Noncanonical combined seasonal checkpoint");
            return state;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed combined seasonal checkpoint",ex);
        }
    }

    public static Prepared prepareUniform(HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices, double durationSeconds) {
        return prepare(expected,atlas,forcing,lakes,loaded,slices,null,durationSeconds,false);
    }

    public static Prepared prepareScheduled(HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded, double durationSeconds) {
        return prepare(expected,atlas,forcing,lakes,loaded,0,
            Objects.requireNonNull(schedules),durationSeconds,false);
    }

    /**
     * Opt-in streaming transition: previously persisted snow/soil cells may be
     * unloaded, but MUST still receive explicit weather forcing. Their snow
     * and soil stores advance normally; runoff produced at unloaded vertices
     * is conserved in the pending frontier until those vertices load.
     *
     * Forcing must cover exactly loaded cells plus every persisted snow/soil
     * cell, with no unknown unloaded owners. This does not simulate never-
     * visited cells or infer weather, and is not a distributed transaction.
     */
    public static Prepared prepareStreamingUniform(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices, double durationSeconds) {
        return prepare(expected,atlas,forcing,lakes,loaded,slices,null,durationSeconds,true);
    }

    public static Prepared prepareStreamingScheduled(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded, double durationSeconds) {
        return prepare(expected,atlas,forcing,lakes,loaded,0,
            Objects.requireNonNull(schedules),durationSeconds,true);
    }

    private static Prepared prepare(HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            double durationSeconds, boolean streaming) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(forcing);
        Objects.requireNonNull(lakes);
        Objects.requireNonNull(loaded);
        if (loaded.isEmpty() || !forcing.keySet().containsAll(loaded) ||
            !Double.isFinite(durationSeconds) || durationSeconds <= 0)
            throw new IllegalArgumentException("Incomplete seasonal snow/soil ownership");
        var previous = decode(expected.worldState());
        var oldSnow = HydrologySnowSoilStateV2.decode(previous.snowState());
        if (streaming) {
            var required = new HashSet<GlobalHydrologyAddressV2.Sample>(loaded);
            required.addAll(oldSnow.cells().keySet());
            if (!forcing.keySet().equals(required))
                throw new IllegalArgumentException("Streaming forcing must cover loaded and persisted cells");
        } else if (!forcing.keySet().equals(loaded) ||
                   !loaded.containsAll(oldSnow.cells().keySet())) {
            throw new IllegalArgumentException("Persisted snow/soil cell is unloaded");
        }
        uniquePhysical(forcing.keySet());
        for (var entry : forcing.entrySet()) {
            var address = Objects.requireNonNull(entry.getKey());
            atlas.routeAt(address);
            var f = Objects.requireNonNull(entry.getValue());
            double area = HydrologySphericalCellAreaV2.areaAt(
                address,previous.radiusMeters());
            if (Math.abs(area-f.cellArea()) >
                Math.max(1e-12,area*1e-10))
                throw new IllegalArgumentException("Seasonal cell area differs from planet");
        }
        var snow = HydrologySnowSoilStateV2.advanceAudited(oldSnow,forcing);
        var owned = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var deferred = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var source : snow.runoff()) {
            if (!loaded.contains(source.address()) && source.localWater() == 0.0)
                continue; // Zero runoff is not an in-flight water receipt.
            var target = loaded.contains(source.address()) ? owned : deferred;
            if (target.putIfAbsent(source.address(),source.localWater()) != null)
                throw new IllegalArgumentException("Duplicate seasonal runoff source");
        }
        // Deferred runoff is already counted as precipitation-derived runoff
        // in the snow/soil budget. It is not a new external receipt.
        String topologyState = previous.topologyState();
        if (!deferred.isEmpty()) {
            var bound = HydrologySeasonalTopologyEpochV2.decode(topologyState);
            var outlet = HydrologySeasonalOutletSettlementV2.decode(bound.waterState());
            var prior = outlet.lakes();
            var pending = HydrologySeasonalLakeInboxCommitV2.combined(
                prior.pendingM3(),deferred);
            var augmented = new HydrologySeasonalLakeCarryoverStateV2.State(
                prior.world(),prior.season(),prior.lakeStorageM3(),pending);
            topologyState = HydrologySeasonalTopologyEpochV2.encode(
                new HydrologySeasonalTopologyEpochV2.Bound(bound.snapshotId(),
                    HydrologySeasonalOutletSettlementV2.encode(
                        new HydrologySeasonalOutletSettlementV2.State(
                            augmented,outlet.dischargedM3()))));
        }
        var inner = new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            topologyState,expected.inboxState());
        var water = schedules == null ?
            HydrologySeasonalTopologyEpochV2.prepareOwnedUniform(
                inner,atlas,owned,lakes,loaded,slices,durationSeconds) :
            HydrologySeasonalTopologyEpochV2.prepareOwnedScheduled(
                inner,atlas,owned,lakes,schedules,loaded,durationSeconds);
        audit(snow,water,deferred);
        var next = new HydrologyCheckpointV2.Checkpoint(
            water.checkpoint().epoch(),
            encode(new State(previous.radiusMeters(),
                water.checkpoint().worldState(),
                HydrologySnowSoilStateV2.encode(snow.state()))),
            water.checkpoint().inboxState());
        return new Prepared(snow,water,next);
    }

    /** Streaming variants commit the same complete local snow/water/inbox epoch. */
    public static Prepared commitStreamingUniform(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices, double durationSeconds) throws IOException {
        var result = prepareStreamingUniform(expected,atlas,forcing,lakes,loaded,slices,durationSeconds);
        HydrologyCheckpointStoreV2.saveNext(file,expected,result.checkpoint());
        return result;
    }

    public static Prepared commitStreamingScheduled(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            double durationSeconds) throws IOException {
        var result = prepareStreamingScheduled(expected,atlas,forcing,lakes,schedules,loaded,durationSeconds);
        HydrologyCheckpointStoreV2.saveNext(file,expected,result.checkpoint());
        return result;
    }

    /** Commit the audited snow/soil and water transition as ONE local file. */
    public static Prepared commitUniform(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices, double durationSeconds) throws IOException {
        var result = prepareUniform(expected,atlas,forcing,lakes,loaded,slices,durationSeconds);
        HydrologyCheckpointStoreV2.saveNext(file,expected,result.checkpoint());
        return result;
    }

    public static Prepared commitScheduled(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            double durationSeconds) throws IOException {
        var result = prepareScheduled(expected,atlas,forcing,lakes,schedules,loaded,durationSeconds);
        HydrologyCheckpointStoreV2.saveNext(file,expected,result.checkpoint());
        return result;
    }

    private static void audit(HydrologySnowSoilStateV2.Step snow,
            HydrologySeasonalTopologyEpochV2.Prepared water,
            Map<GlobalHydrologyAddressV2.Sample,Double> deferred) {
        var w = water.water();
        // The old-water routing input is the sum of prior pending frontier
        // water and newly claimed receipts. Fresh snow/soil runoff is already
        // accounted for by the precipitation/store balance, not added again.
        BigDecimal inputs = number(snow.initialVolume())
            .add(number(snow.precipitationVolume()))
            .add(number(w.oldWaterRouting().inputM3()))
            .subtract(sum(deferred))
            .add(number(w.lakeStep().result().previousLakeStorageM3()));
        BigDecimal outputs = number(snow.storedVolume())
            .add(number(snow.evaporationVolume()))
            .add(number(w.lakeStep().result().lakeEvaporationM3()))
            .add(sum(w.state().lakes().pendingM3()))
            .add(sum(w.state().lakes().lakeStorageM3()))
            .add(sum(w.newOceanDischargeM3()));
        if (inputs.signum()<0)
            throw new IllegalArgumentException("Deferred runoff exceeds available water");
        BigDecimal tolerance = inputs.abs().max(outputs.abs())
            .multiply(BigDecimal.valueOf(1e-10))
            .max(BigDecimal.valueOf(1e-8));
        if (inputs.subtract(outputs).abs().compareTo(tolerance)>0)
            throw new IllegalArgumentException("Coupled seasonal water budget mismatch");
    }

    private static BigDecimal sum(Map<GlobalHydrologyAddressV2.Sample,Double> values) {
        BigDecimal result = BigDecimal.ZERO;
        for (double v:values.values()) result=result.add(number(v));
        return result;
    }

    private static BigDecimal number(double value) {
        if (!Double.isFinite(value) || value<0)
            throw new IllegalArgumentException("Invalid seasonal water volume");
        return BigDecimal.valueOf(value);
    }

    private static void uniquePhysical(Collection<GlobalHydrologyAddressV2.Sample> addresses) {
        var seen = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var address : addresses) {
            var sample = Objects.requireNonNull(address);
            if (!HydrologySphericalTopologyV2.isCanonical(sample))
                throw new IllegalArgumentException("Noncanonical seasonal owner");
            var old = seen.putIfAbsent(
                HydrologyGlobalCatchmentsV2.coordinate(sample),sample);
            if (old != null && !old.equals(sample))
                throw new IllegalArgumentException("Duplicate physical seasonal owner");
        }
    }
}
