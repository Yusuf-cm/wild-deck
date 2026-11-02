package com.wilddeck.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in local checkpoint settlement of previously conserved frontier water at
 * caller-verified ocean mouths. Lake volumes, pending water, cumulative outlet
 * discharge and the unchanged receipt inbox share one atomic checkpoint.
 *
 * This is a zero-forcing transition epoch, NOT a seasonal climate/lake step.
 * It does not acknowledge external senders or authenticate ocean geography.
 * The envelope is intentionally distinct from the legacy lake-only codec:
 * callers must decode it here until the full seasonal runner is integrated.
 */
public final class HydrologySeasonalOutletSettlementV2 {
    private HydrologySeasonalOutletSettlementV2() {}
    private static final String MAGIC = "WD-OUTLET-SETTLEMENT-1";
    private static final int LIMIT = 100_000;

    public record State(HydrologySeasonalLakeCarryoverStateV2.State lakes,
            Map<GlobalHydrologyAddressV2.Sample,Double> dischargedM3) {
        public State {
            Objects.requireNonNull(lakes);
            Objects.requireNonNull(dischargedM3);
            if (dischargedM3.size()>LIMIT)
                throw new IllegalArgumentException("Outlet ledger exceeds entry budget");
            var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            var owners=new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
                GlobalHydrologyAddressV2.Sample>();
            for (var entry:dischargedM3.entrySet()) {
                var address=Objects.requireNonNull(entry.getKey());
                if (!HydrologySphericalTopologyV2.isCanonical(address))
                    throw new IllegalArgumentException("Noncanonical ocean mouth");
                var prior=owners.putIfAbsent(
                    HydrologyGlobalCatchmentsV2.coordinate(address),address);
                if (prior!=null && !prior.equals(address))
                    throw new IllegalArgumentException("Aliased ocean mouths");
                double value=Objects.requireNonNull(entry.getValue());
                if (!Double.isFinite(value) || value<0)
                    throw new IllegalArgumentException("Invalid cumulative ocean volume");
                ordered.put(address,value==0?0.0:value);
            }
            dischargedM3=Collections.unmodifiableMap(ordered);
        }
    }

    public record Prepared(State state, HydrologyCheckpointV2.Checkpoint checkpoint,
            HydrologySeasonalPendingOutletV2.Result routing) {
        public Prepared {
            Objects.requireNonNull(state);
            Objects.requireNonNull(checkpoint);
            Objects.requireNonNull(routing);
        }
    }

    /** Accept a legacy lake checkpoint with an initially empty outlet ledger. */
    public static State decode(String payload) {
        Objects.requireNonNull(payload);
        if (!payload.startsWith(MAGIC+"\n"))
            return new State(HydrologySeasonalLakeCarryoverStateV2.decode(payload),Map.of());
        var lines=payload.split("\\n",-1);
        if (lines.length<4 || !MAGIC.equals(lines[0]) ||
            !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Malformed outlet ledger");
        try {
            var lakes=HydrologySeasonalLakeCarryoverStateV2.decode(
                new String(Base64.getDecoder().decode(lines[1]),StandardCharsets.UTF_8));
            int count=Integer.parseInt(lines[2]);
            if (count<0 || count>LIMIT || lines.length!=count+4)
                throw new IllegalArgumentException("Invalid outlet ledger count");
            var map=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
            for (int i=0;i<count;i++) {
                var fields=lines[i+3].split(",",-1);
                if (fields.length!=5)
                    throw new IllegalArgumentException("Malformed outlet ledger entry");
                var key=new GlobalHydrologyAddressV2.Sample(
                    Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]),Long.parseLong(fields[3]));
                if (map.putIfAbsent(key,Double.parseDouble(fields[4]))!=null)
                    throw new IllegalArgumentException("Duplicate outlet ledger entry");
            }
            var state=new State(lakes,map);
            if (!encode(state).equals(payload))
                throw new IllegalArgumentException("Noncanonical outlet ledger");
            return state;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid outlet ledger encoding",ex);
        }
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var out=new StringBuilder(MAGIC).append('\n')
            .append(Base64.getEncoder().encodeToString(
                HydrologySeasonalLakeCarryoverStateV2.encode(state.lakes())
                    .getBytes(StandardCharsets.UTF_8))).append('\n')
            .append(state.dischargedM3().size()).append('\n');
        for (var entry:state.dischargedM3().entrySet()) {
            var id=entry.getKey();
            out.append(id.level()).append(',').append(id.divisions()).append(',')
                .append(id.x()).append(',').append(id.y()).append(',')
                .append(Double.toHexString(entry.getValue())).append('\n');
        }
        if (out.length()>16*1024*1024)
            throw new IllegalArgumentException("Outlet ledger payload too large");
        return out.toString();
    }

    /**
     * Read-only settlement. Verified mouths must be explicit terminal nodes;
     * unresolved/lake-bound water stays in the checkpoint. Receipt markers are
     * preserved exactly, and the old pending volume is never counted twice.
     */
    public static Prepared prepare(HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakeInlets,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        Objects.requireNonNull(expected);
        var previous=decode(expected.worldState());
        if (previous.lakes().season()!=expected.epoch())
            throw new IllegalArgumentException("Outlet settlement epoch mismatch");
        if (!previous.lakes().lakeStorageM3().keySet().containsAll(lakeInlets))
            throw new IllegalArgumentException("Unknown lake inlet in settlement");
        return prepareRouted(expected,previous,previous.lakes().pendingM3(),
            expected.inboxState(),downstream,loaded,lakeInlets,verifiedMouths);
    }

    /**
     * Claim available boundary receipts and settle them together with old
     * pending water. Delivery markers, frontier and ocean ledger are committed
     * in one checkpoint; future/unloaded receipts remain unclaimed.
     */
    public static Prepared prepareReceived(HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakeInlets,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        Objects.requireNonNull(expected);
        var previous=decode(expected.worldState());
        if (previous.lakes().season()!=expected.epoch())
            throw new IllegalArgumentException("Outlet settlement epoch mismatch");
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(expected.epoch(),Objects.requireNonNull(loaded));
        var combined=HydrologySeasonalLakeInboxCommitV2.combined(
            previous.lakes().pendingM3(),claimed);
        return prepareRouted(expected,previous,combined,
            HydrologyInboxCodecV2.encode(inbox.snapshot()),
            downstream,loaded,lakeInlets,verifiedMouths);
    }

    private static Prepared prepareRouted(HydrologyCheckpointV2.Checkpoint expected,
            State previous,Map<GlobalHydrologyAddressV2.Sample,Double> water,
            String inboxState,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakeInlets,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        if (!previous.lakes().lakeStorageM3().keySet().containsAll(lakeInlets))
            throw new IllegalArgumentException("Unknown lake inlet in settlement");
        var routed=HydrologySeasonalPendingOutletV2.route(
            water,downstream,loaded,lakeInlets,verifiedMouths);
        var totals=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        totals.putAll(previous.dischargedM3());
        for (var entry:routed.verifiedOutletM3().entrySet()) {
            double next=totals.getOrDefault(entry.getKey(),0.0)+entry.getValue();
            if (!Double.isFinite(next))
                throw new IllegalArgumentException("Cumulative outlet discharge overflow");
            totals.put(entry.getKey(),next);
        }
        // The stored ledger is an audit, not a source of water for a new epoch.
        var lakes=new HydrologySeasonalLakeCarryoverStateV2.State(
            previous.lakes().world(),Math.addExact(expected.epoch(),1),
            previous.lakes().lakeStorageM3(),routed.pendingM3());
        var state=new State(lakes,totals);
        var next=new HydrologyCheckpointV2.Checkpoint(
            lakes.season(),encode(state),inboxState);
        return new Prepared(state,next,routed);
    }

    /** Single-writer atomic checkpoint with stale-world/inbox rejection. */
    public static Prepared commitNext(Path file,HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakeInlets,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) throws IOException {
        var planned=prepare(expected,downstream,loaded,lakeInlets,verifiedMouths);
        HydrologyCheckpointStoreV2.saveNext(file,expected,planned.checkpoint());
        return planned;
    }
    /** Receipt-inclusive local atomic settlement, guarded against stale inboxes. */
    public static Prepared commitReceived(Path file,HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakeInlets,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) throws IOException {
        var planned=prepareReceived(expected,downstream,loaded,lakeInlets,verifiedMouths);
        HydrologyCheckpointStoreV2.saveNext(file,expected,planned.checkpoint());
        return planned;
    }

}
