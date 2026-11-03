package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in local atomic checkpoint joining absolute-tick river transit with the
 * existing snow/soil + topology-bound seasonal water epoch. A transfer is
 * released into the OLD pending-water inventory before the seasonal solve, not
 * into rainfall, so it cannot be counted as new precipitation.
 *
 * One tick represents one seasonal epoch here; travel times and authenticated
 * transfer identities are caller supplied. This is not a distributed
 * sender/receiver transaction, a calibrated hydraulic solver, or a replacement
 * for the legacy seasonal checkpoint format.
 */
public final class HydrologyTimedSeasonalEpochV2 {
    private HydrologyTimedSeasonalEpochV2() {}
    private static final String MAGIC = "WD-TIMED-SEASONAL-1";
    private static final int MAX_PAYLOAD = 72 * 1024 * 1024;

    public record State(String seasonal, String transit) {
        public State {
            Objects.requireNonNull(seasonal);
            Objects.requireNonNull(transit);
            var water = HydrologySeasonalSnowSoilWaterEpochV2.decode(seasonal);
            var snow = HydrologySnowSoilStateV2.decode(water.snowState());
            var queue = HydrologyTimedTransitLedgerV2.decode(transit);
            if (!snow.world().equals(queue.world()))
                throw new IllegalArgumentException("Transit and seasonal worlds differ");
            var topology = HydrologySeasonalTopologyEpochV2.decode(water.topologyState());
            var outlet = HydrologySeasonalOutletSettlementV2.decode(topology.waterState());
            if (queue.tick() != outlet.lakes().season())
                throw new IllegalArgumentException("Transit tick and seasonal lake season differ");
        }
    }

    public record Prepared(HydrologyTimedTransitLedgerV2.Step transit,
                           HydrologySeasonalSnowSoilWaterEpochV2.Prepared seasonal,
                           HydrologyCheckpointV2.Checkpoint checkpoint) {
        public Prepared {
            Objects.requireNonNull(transit);
            Objects.requireNonNull(seasonal);
            Objects.requireNonNull(checkpoint);
        }
    }

    /** Bind only an already initialized seasonal checkpoint with no transit. */
    public static HydrologyCheckpointV2.Checkpoint bindFresh(
            HydrologyCheckpointV2.Checkpoint seasonal) {
        Objects.requireNonNull(seasonal);
        if (seasonal.epoch() != 0)
            throw new IllegalArgumentException("Transit binding requires initial epoch");
        var snow = HydrologySeasonalSnowSoilWaterEpochV2.decode(seasonal.worldState());
        var world = HydrologySnowSoilStateV2.decode(snow.snowState()).world();
        var queue = HydrologyTimedTransitLedgerV2.empty(world);
        return new HydrologyCheckpointV2.Checkpoint(0,
            encode(new State(seasonal.worldState(),
                HydrologyTimedTransitLedgerV2.encode(queue))),
            seasonal.inboxState());
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var value = MAGIC + "\n" +
            Base64.getEncoder().encodeToString(state.seasonal().getBytes(StandardCharsets.UTF_8)) + "\n" +
            Base64.getEncoder().encodeToString(state.transit().getBytes(StandardCharsets.UTF_8)) + "\n";
        if (value.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Timed seasonal checkpoint too large");
        return value;
    }

    public static State decode(String value) {
        Objects.requireNonNull(value);
        if (value.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Timed seasonal checkpoint too large");
        var lines = value.split("\\n",-1);
        if (lines.length != 4 || !MAGIC.equals(lines[0]) || !lines[3].isEmpty())
            throw new IllegalArgumentException("Invalid timed seasonal checkpoint");
        try {
            var state = new State(
                new String(Base64.getDecoder().decode(lines[1]),StandardCharsets.UTF_8),
                new String(Base64.getDecoder().decode(lines[2]),StandardCharsets.UTF_8));
            if (!encode(state).equals(value))
                throw new IllegalArgumentException("Noncanonical timed seasonal checkpoint");
            return state;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed timed seasonal checkpoint",ex);
        }
    }

    public static Prepared prepareStreamingUniform(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Collection<HydrologyTimedTransitLedgerV2.Transfer> incoming,
            int slices, double durationSeconds) {
        return prepareStreaming(expected,atlas,forcing,lakes,loaded,incoming,
            slices,null,durationSeconds);
    }

    /** Chronological lake forcing with the same durable absolute-tick transit settlement. */
    public static Prepared prepareStreamingScheduled(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Collection<HydrologyTimedTransitLedgerV2.Transfer> incoming,
            double durationSeconds) {
        return prepareStreaming(expected,atlas,forcing,lakes,loaded,incoming,
            0,Objects.requireNonNull(schedules),durationSeconds);
    }

    private static Prepared prepareStreaming(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Collection<HydrologyTimedTransitLedgerV2.Transfer> incoming,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            double durationSeconds) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(incoming);
        var previous = decode(expected.worldState());
        var oldQueue = HydrologyTimedTransitLedgerV2.decode(previous.transit());
        if (oldQueue.tick() != expected.epoch())
            throw new IllegalArgumentException("Transit tick and checkpoint epoch differ");
        long next = Math.addExact(expected.epoch(),1);
        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var sample : loaded) {
            Objects.requireNonNull(sample);
            if (!HydrologySphericalTopologyV2.isCanonical(sample))
                throw new IllegalArgumentException("Noncanonical loaded transit owner");
            var coordinate = HydrologyGlobalCatchmentsV2.coordinate(sample);
            if (owners.putIfAbsent(coordinate,sample) != null)
                throw new IllegalArgumentException("Duplicate physical loaded transit owner");
            atlas.routeAt(sample);
        }
        var transit = HydrologyTimedTransitLedgerV2.advance(oldQueue,incoming,
            next,owners.keySet());
        var oldSeasonal = HydrologySeasonalSnowSoilWaterEpochV2.decode(previous.seasonal());
        var topology = HydrologySeasonalTopologyEpochV2.decode(oldSeasonal.topologyState());
        var outlet = HydrologySeasonalOutletSettlementV2.decode(topology.waterState());
        var lake = outlet.lakes();
        var released = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : transit.releasedM3().entrySet()) {
            var owner = owners.get(entry.getKey());
            if (owner == null)
                throw new IllegalArgumentException("Released transit has no loaded owner");
            released.put(owner,entry.getValue());
        }
        var pending = HydrologySeasonalLakeInboxCommitV2.combined(
            lake.pendingM3(),released);
        // Do not settle a durable transit ID if its water disappears when
        // merged into a much larger double-valued pending inventory.
        for (var entry : released.entrySet()) {
            double old = lake.pendingM3().getOrDefault(entry.getKey(),0.0);
            BigDecimal exact = BigDecimal.valueOf(old).add(
                BigDecimal.valueOf(entry.getValue()));
            if (BigDecimal.valueOf(pending.get(entry.getKey())).compareTo(exact) != 0)
                throw new IllegalArgumentException(
                    "Timed transfer would lose precision in pending water");
        }
        var augmented = new HydrologySeasonalLakeCarryoverStateV2.State(
            lake.world(),lake.season(),lake.lakeStorageM3(),pending);
        var updatedTopology = HydrologySeasonalTopologyEpochV2.encode(
            new HydrologySeasonalTopologyEpochV2.Bound(topology.snapshotId(),
                HydrologySeasonalOutletSettlementV2.encode(
                    new HydrologySeasonalOutletSettlementV2.State(
                        augmented,outlet.dischargedM3()))));
        var inner = new HydrologyCheckpointV2.Checkpoint(expected.epoch(),
            HydrologySeasonalSnowSoilWaterEpochV2.encode(
                new HydrologySeasonalSnowSoilWaterEpochV2.State(
                    oldSeasonal.radiusMeters(),updatedTopology,oldSeasonal.snowState())),
            expected.inboxState());
        var seasonal = schedules == null ?
            HydrologySeasonalSnowSoilWaterEpochV2.prepareStreamingUniform(
                inner,atlas,forcing,lakes,loaded,slices,durationSeconds) :
            HydrologySeasonalSnowSoilWaterEpochV2.prepareStreamingScheduled(
                inner,atlas,forcing,lakes,schedules,loaded,durationSeconds);
        if (seasonal.checkpoint().epoch() != next ||
            transit.state().tick() != next)
            throw new IllegalArgumentException("Nonatomic seasonal/transit tick");
        var checkpoint = new HydrologyCheckpointV2.Checkpoint(next,
            encode(new State(seasonal.checkpoint().worldState(),
                HydrologyTimedTransitLedgerV2.encode(transit.state()))),
            seasonal.checkpoint().inboxState());
        return new Prepared(transit,seasonal,checkpoint);
    }

    /** Compare-and-swap persistence of BOTH transit settlement and water state. */
    public static Prepared commitStreamingUniform(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Collection<HydrologyTimedTransitLedgerV2.Transfer> incoming,
            int slices, double durationSeconds) throws IOException {
        var result = prepareStreamingUniform(expected,atlas,forcing,lakes,
            loaded,incoming,slices,durationSeconds);
        HydrologyCheckpointStoreV2.saveNext(file,expected,result.checkpoint());
        return result;
    }

    /** Persist chronological lake forcing and timed transit in one local CAS checkpoint. */
    public static Prepared commitStreamingScheduled(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Collection<HydrologyTimedTransitLedgerV2.Transfer> incoming,
            double durationSeconds) throws IOException {
        var result = prepareStreamingScheduled(expected,atlas,forcing,lakes,
            schedules,loaded,incoming,durationSeconds);
        HydrologyCheckpointStoreV2.saveNext(file,expected,result.checkpoint());
        return result;
    }

}
