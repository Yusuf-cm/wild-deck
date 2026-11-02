package com.wilddeck.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in topology-fenced seasonal water checkpoint. The complete, independently
 * verified spherical atlas supplies the drainage snapshot identity; the caller
 * may expose only a loaded subset of its edges. A changed atlas is rejected
 * before any receipt is claimed or water is committed.
 *
 * This does not migrate old topology, prove ocean geography, or coordinate
 * snow/soil, sediment, erosion, or distributed writers.
 */
public final class HydrologySeasonalTopologyEpochV2 {
    private HydrologySeasonalTopologyEpochV2() {}
    private static final String MAGIC = "WD-SEASONAL-TOPOLOGY-1";
    private static final int MAX_BYTES = 24 * 1024 * 1024;

    public record Bound(String snapshotId, String waterState) {
        public Bound {
            Objects.requireNonNull(snapshotId);
            Objects.requireNonNull(waterState);
            if (!snapshotId.matches("[0-9a-f]{64}") ||
                waterState.length() > 16 * 1024 * 1024)
                throw new IllegalArgumentException("Invalid seasonal drainage binding");
            HydrologySeasonalOutletSettlementV2.decode(waterState);
        }
    }

    public record Prepared(String snapshotId,
            HydrologySeasonalWaterEpochV2.Prepared water,
            HydrologyCheckpointV2.Checkpoint checkpoint) {
        public Prepared {
            Objects.requireNonNull(snapshotId);
            Objects.requireNonNull(water);
            Objects.requireNonNull(checkpoint);
        }
    }

    /**
     * Bind only a fresh, empty hydrology checkpoint. An existing water or
     * receipt history cannot safely be retroactively attributed to an atlas.
     */
    public static HydrologyCheckpointV2.Checkpoint bindFresh(
            HydrologyCheckpointV2.Checkpoint checkpoint,
            HydrologySphericalRouteAtlasV2 atlas) {
        Objects.requireNonNull(checkpoint);
        var state=HydrologySeasonalOutletSettlementV2.decode(checkpoint.worldState());
        var lakes=state.lakes();
        var inbox=HydrologyCheckpointV2.restoreInbox(checkpoint).snapshot();
        if (lakes.season()!=checkpoint.epoch() ||
            !lakes.pendingM3().isEmpty() ||
            lakes.lakeStorageM3().values().stream().anyMatch(v->v!=0.0) ||
            !state.dischargedM3().isEmpty() ||
            !inbox.receipts().isEmpty() || !inbox.delivered().isEmpty() ||
            !inbox.consumedEpochs().isEmpty())
            throw new IllegalArgumentException("Cannot bind in-flight water to new topology");
        return new HydrologyCheckpointV2.Checkpoint(checkpoint.epoch(),
            encode(new Bound(HydrologyDrainageSnapshotIdV2.of(atlas),
                checkpoint.worldState())),checkpoint.inboxState());
    }

    public static String encode(Bound state) {
        Objects.requireNonNull(state);
        String payload=MAGIC+"\n"+state.snapshotId()+"\n"+
            Base64.getEncoder().encodeToString(
                state.waterState().getBytes(StandardCharsets.UTF_8))+"\n";
        if (payload.length()>MAX_BYTES)
            throw new IllegalArgumentException("Seasonal drainage payload too large");
        return payload;
    }

    public static Bound decode(String payload) {
        Objects.requireNonNull(payload);
        if (payload.length()>MAX_BYTES)
            throw new IllegalArgumentException("Seasonal drainage payload too large");
        var lines=payload.split("\\n",-1);
        if (lines.length!=4 || !MAGIC.equals(lines[0]) ||
            !lines[3].isEmpty())
            throw new IllegalArgumentException("Unsupported seasonal drainage payload");
        try {
            var decoded=new String(Base64.getDecoder().decode(lines[2]),
                StandardCharsets.UTF_8);
            var state=new Bound(lines[1],decoded);
            if (!encode(state).equals(payload))
                throw new IllegalArgumentException("Noncanonical seasonal drainage payload");
            return state;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid seasonal drainage payload",ex);
        }
    }

    public static Prepared prepareUniform(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> mouths) {
        return prepare(expected,atlas,runoff,lakes,slices,null,downstream,loaded,mouths);
    }

    public static Prepared prepareScheduled(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> mouths) {
        return prepare(expected,atlas,runoff,lakes,0,Objects.requireNonNull(schedules),
            downstream,loaded,mouths);
    }

    private static Prepared prepare(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> mouths) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(runoff);
        Objects.requireNonNull(lakes);
        Objects.requireNonNull(downstream);
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(mouths);
        var bound=decode(expected.worldState());
        String snapshot=HydrologyDrainageSnapshotIdV2.of(atlas);
        if (!bound.snapshotId().equals(snapshot))
            throw new IllegalArgumentException("Seasonal drainage topology changed");
        verifyAtlas(atlas,runoff,lakes,downstream,loaded,mouths);
        var inner=new HydrologyCheckpointV2.Checkpoint(
            expected.epoch(),bound.waterState(),expected.inboxState());
        var advanced=schedules==null ?
            HydrologySeasonalWaterEpochV2.prepareUniform(
                inner,runoff,lakes,slices,downstream,loaded,mouths) :
            HydrologySeasonalWaterEpochV2.prepareScheduled(
                inner,runoff,lakes,schedules,downstream,loaded,mouths);
        var next=new HydrologyCheckpointV2.Checkpoint(
            advanced.checkpoint().epoch(),
            encode(new Bound(snapshot,advanced.checkpoint().worldState())),
            advanced.checkpoint().inboxState());
        return new Prepared(snapshot,advanced,next);
    }

    private static void verifyAtlas(HydrologySphericalRouteAtlasV2 atlas,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> mouths) {
        for (var address:loaded) atlas.routeAt(address);
        for (var entry:lakes.entrySet()) {
            var route=atlas.routeAt(entry.getKey());
            if (!Objects.equals(route.downstream(),entry.getValue().spillTo()))
                throw new IllegalArgumentException(
                    "Lake spill contradicts bound drainage atlas");
        }
        for (var address:runoff.drainage().pendingOutflow().keySet())
            atlas.routeAt(address);
        for (var address:runoff.drainage().terminalOutflow().keySet()) {
            if (!atlas.routeAt(address).terminal() || !mouths.contains(address))
                throw new IllegalArgumentException("Seasonal runoff has unverified mouth");
        }
        for (var mouth:mouths) {
            var node=atlas.routeAt(mouth);
            if (!node.terminal() || node.downstream()!=null)
                throw new IllegalArgumentException("Mouth contradicts drainage atlas");
        }
        for (var edge:downstream.entrySet()) {
            var node=atlas.routeAt(edge.getKey());
            if (!Objects.equals(node.downstream(),edge.getValue()))
                throw new IllegalArgumentException("Drainage edge contradicts atlas");
            if (edge.getValue()!=null) atlas.routeAt(edge.getValue());
        }
    }

    /**
     * Derive a seasonal graph from the bound atlas instead of trusting an
     * independently supplied route graph. Every loaded physical vertex owns
     * exactly one local runoff value, including zero. Unloaded downstream
     * vertices receive pending water, never invented ocean discharge.
     *
     * The supplied runoff must already have been audited against precipitation
     * and snow/soil storage; this helper cannot infer missing rainfall.
     */
    public static HydrologySeasonalGlobalRoutingV2.Result routeOwnedRunoff(
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,Double> ownedRunoffM3,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            double durationSeconds) {
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(ownedRunoffM3);
        Objects.requireNonNull(loaded);
        if (!Double.isFinite(durationSeconds) || durationSeconds<=0 ||
            loaded.isEmpty() || loaded.size()>1_000_000 ||
            !ownedRunoffM3.keySet().equals(loaded))
            throw new IllegalArgumentException("Incomplete seasonal rainfall ownership");
        var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        ordered.putAll(ownedRunoffM3);
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>(ordered.size());
        var reaches=new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyGlobalCatchmentsV2.ReachId>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry:ordered.entrySet()) {
            var atlasNode=atlas.routeAt(entry.getKey());
            double volume=Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(volume) || volume<0)
                throw new IllegalArgumentException("Invalid owned seasonal runoff");
            nodes.add(new HydrologyFlowGraphV2.Node(entry.getKey(),volume,
                atlasNode.downstream(),atlasNode.terminal()));
            if (atlasNode.downstream()!=null)
                reaches.put(entry.getKey(),new HydrologyGlobalCatchmentsV2.ReachId(
                    HydrologyGlobalCatchmentsV2.coordinate(entry.getKey()),
                    HydrologyGlobalCatchmentsV2.coordinate(atlasNode.downstream())));
        }
        var flow=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,flow);
        var catchments=new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyGlobalCatchmentsV2.CatchmentId>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry:flow.resolvedCatchments().entrySet())
            catchments.put(entry.getKey(),
                new HydrologyGlobalCatchmentsV2.CatchmentId(
                    HydrologyGlobalCatchmentsV2.coordinate(entry.getValue())));
        return new HydrologySeasonalGlobalRoutingV2.Result(
            flow,budget,catchments,reaches,
            rates(flow.terminalOutflow(),durationSeconds),
            rates(flow.pendingOutflow(),durationSeconds),durationSeconds);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> rates(
            Map<GlobalHydrologyAddressV2.Sample,Double> volumes,
            double durationSeconds) {
        var result=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry:volumes.entrySet()) {
            double rate=entry.getValue()/durationSeconds;
            if (!Double.isFinite(rate) || rate<0)
                throw new IllegalArgumentException("Seasonal discharge rate overflow");
            result.put(entry.getKey(),rate);
        }
        return result;
    }

    public static Prepared commitUniform(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> mouths) throws IOException {
        var prepared=prepareUniform(expected,atlas,runoff,lakes,slices,
            downstream,loaded,mouths);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }

    public static Prepared commitScheduled(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> mouths) throws IOException {
        var prepared=prepareScheduled(expected,atlas,runoff,lakes,schedules,
            downstream,loaded,mouths);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }
}
