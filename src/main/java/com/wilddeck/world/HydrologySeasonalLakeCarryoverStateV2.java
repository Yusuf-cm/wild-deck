package com.wilddeck.world;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in canonical checkpoint payload for seasonal lake storage AND unresolved
 * frontier water. These must be persisted together: persisting only lake volume
 * can lose delayed spill, while persisting only spill can duplicate water.
 *
 * A State can be stored as the opaque worldState in an existing atomic
 * HydrologyCheckpointV2.Checkpoint. This codec does not commit that checkpoint,
 * advance other world systems, or provide a distributed writer lock.
 */
public final class HydrologySeasonalLakeCarryoverStateV2 {
    private HydrologySeasonalLakeCarryoverStateV2() {}
    private static final String MAGIC="WD-LAKE-CARRYOVER-1";
    private static final int MAX_LAKES=4096;
    private static final int MAX_PENDING=100_000;
    private static final int MAX_BYTES=16*1024*1024;

    public record State(String world,long season,
            Map<GlobalHydrologyAddressV2.Sample,Double> lakeStorageM3,
            Map<GlobalHydrologyAddressV2.Sample,Double> pendingM3) {
        public State {
            Objects.requireNonNull(world);
            if (world.isBlank() || season<0)
                throw new IllegalArgumentException("Invalid lake checkpoint identity");
            lakeStorageM3=ordered(lakeStorageM3,MAX_LAKES);
            pendingM3=ordered(pendingM3,MAX_PENDING);
            var lakePhysical=new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
                GlobalHydrologyAddressV2.Sample>();
            for (var address:lakeStorageM3.keySet())
                unique(lakePhysical,address);
            var pendingPhysical=new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
                GlobalHydrologyAddressV2.Sample>();
            for (var address:pendingM3.keySet()) {
                unique(pendingPhysical,address);
                var old=lakePhysical.get(HydrologyGlobalCatchmentsV2.coordinate(address));
                if (old!=null && !old.equals(address))
                    throw new IllegalArgumentException("Aliased lake and frontier ownership");
            }
        }
    }

    /**
     * Verify that the exact previously persisted frontier was consumed by
     * the audited lake step, and that each lake started with the persisted
     * volume. A stale or duplicate season cannot be applied to this state.
     * No I/O occurs; persist the returned State atomically with the epoch.
     */
    public static State advance(State previous,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            HydrologySeasonalLaggedLakeNetworkV2.CarryoverResult candidate) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(forcing);
        Objects.requireNonNull(candidate);
        if (!previous.pendingM3().equals(candidate.carryoverM3()) ||
            !previous.lakeStorageM3().keySet().equals(forcing.keySet()) ||
            !previous.lakeStorageM3().keySet().equals(candidate.result().lakes().keySet()))
            throw new IllegalArgumentException("Stale lake storage or frontier replay");
        BigDecimal oldStorage=BigDecimal.ZERO, pending=BigDecimal.ZERO,
                   nextStorage=BigDecimal.ZERO, nextPending=BigDecimal.ZERO;
        var updated=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry:previous.lakeStorageM3().entrySet()) {
            var lake=Objects.requireNonNull(forcing.get(entry.getKey()));
            if (Double.compare(entry.getValue(),lake.previousVolumeM3())!=0)
                throw new IllegalArgumentException("Lake forcing has stale storage");
            oldStorage=oldStorage.add(number(entry.getValue()));
            var value=candidate.result().lakes().get(entry.getKey()).volume();
            if (value>lake.basin().capacity())
                throw new IllegalArgumentException("Lake exceeds basin capacity");
            nextStorage=nextStorage.add(number(value));
            updated.put(entry.getKey(),value);
        }
        for (var value:previous.pendingM3().values()) pending=pending.add(number(value));
        for (var value:candidate.result().pendingM3().values())
            nextPending=nextPending.add(number(value));
        var result=candidate.result();
        if (!close(oldStorage,number(result.previousLakeStorageM3())) ||
            !close(nextStorage,number(result.finalLakeStorageM3())) ||
            !close(number(result.seasonalRunoffM3()).add(oldStorage).add(pending),
                number(result.verifiedOutletM3()).add(nextStorage)
                    .add(nextPending).add(number(result.lakeEvaporationM3()))) ||
            Math.abs(result.residualM3())>1e-8*
                Math.max(1,Math.abs(result.seasonalRunoffM3())+
                    result.previousLakeStorageM3()+pending.doubleValue()))
            throw new IllegalArgumentException("Seasonal lake checkpoint budget mismatch");
        return new State(previous.world(),Math.addExact(previous.season(),1),
            updated,result.pendingM3());
    }

    /**
     * Persist a verified lake/carryover season in the SAME local atomic
     * checkpoint file as the existing hydrology inbox. The expected checkpoint
     * must contain this codec and have an epoch matching its season. The inbox
     * is preserved byte-for-byte; external receipt consumption and sediment
     * epochs must be coordinated by a separate authoritative world runner.
     *
     * saveNext rejects stale snapshots in the supported single-writer model.
     * This is not a distributed lock, multi-file transaction or cloud CAS.
     */
    public static State commitNext(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySeasonalLaggedLakeNetworkV2.Lake> forcing,
            HydrologySeasonalLaggedLakeNetworkV2.CarryoverResult candidate)
            throws IOException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(expected);
        var previous=decode(expected.worldState());
        if (expected.epoch()!=previous.season())
            throw new IllegalArgumentException("Checkpoint epoch and lake season differ");
        var next=advance(previous,forcing,candidate);
        var checkpoint=new HydrologyCheckpointV2.Checkpoint(
            next.season(),encode(next),expected.inboxState());
        HydrologyCheckpointStoreV2.saveNext(file,expected,checkpoint);
        return next;
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var out=new StringBuilder(MAGIC).append('\n')
            .append(Base64.getEncoder().encodeToString(
                state.world().getBytes(StandardCharsets.UTF_8))).append('\n')
            .append(state.season()).append('\n')
            .append(state.lakeStorageM3().size()).append('\n');
        append(out,state.lakeStorageM3());
        out.append(state.pendingM3().size()).append('\n');
        append(out,state.pendingM3());
        if (out.length()>MAX_BYTES)
            throw new IllegalArgumentException("Lake checkpoint payload too large");
        return out.toString();
    }

    public static State decode(String encoded) {
        Objects.requireNonNull(encoded);
        if (encoded.length()>MAX_BYTES)
            throw new IllegalArgumentException("Lake checkpoint payload too large");
        var lines=encoded.split("\\n",-1);
        if (lines.length<6 || !MAGIC.equals(lines[0]) ||
            !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Unsupported lake checkpoint payload");
        try {
            String world=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(
                Base64.getDecoder().decode(lines[1]))).toString();
            long season=Long.parseLong(lines[2]);
            int lakeCount=Integer.parseInt(lines[3]);
            if (lakeCount<0 || lakeCount>MAX_LAKES || 4L+lakeCount>=lines.length)
                throw new IllegalArgumentException("Invalid lake checkpoint count");
            var lakes=parse(lines,4,lakeCount);
            int pendingIndex=4+lakeCount;
            int pendingCount=Integer.parseInt(lines[pendingIndex]);
            if (pendingCount<0 || pendingCount>MAX_PENDING ||
                (long)pendingIndex+pendingCount+2!=lines.length)
                throw new IllegalArgumentException("Invalid pending checkpoint count");
            var pending=parse(lines,pendingIndex+1,pendingCount);
            var state=new State(world,season,lakes,pending);
            if (!encode(state).equals(encoded))
                throw new IllegalArgumentException("Noncanonical lake checkpoint payload");
            return state;
        } catch (NumberFormatException|CharacterCodingException ex) {
            throw new IllegalArgumentException("Malformed lake checkpoint payload",ex);
        }
    }

    private static void append(StringBuilder out,
            Map<GlobalHydrologyAddressV2.Sample,Double> values) {
        for (var entry:values.entrySet()) {
            var id=entry.getKey();
            out.append(id.level()).append(',').append(id.divisions()).append(',')
                .append(id.x()).append(',').append(id.y()).append(',')
                .append(Double.toHexString(entry.getValue())).append('\n');
        }
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> parse(
            String[] lines,int offset,int count) {
        var values=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (int i=0;i<count;i++) {
            var fields=lines[offset+i].split(",",-1);
            if (fields.length!=5)
                throw new IllegalArgumentException("Invalid lake checkpoint record");
            var id=new GlobalHydrologyAddressV2.Sample(
                Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                Long.parseLong(fields[2]),Long.parseLong(fields[3]));
            var value=Double.parseDouble(fields[4]);
            if (values.putIfAbsent(id,value)!=null)
                throw new IllegalArgumentException("Duplicate lake checkpoint address");
        }
        return values;
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> ordered(
            Map<GlobalHydrologyAddressV2.Sample,Double> input,int limit) {
        Objects.requireNonNull(input);
        if (input.size()>limit)
            throw new IllegalArgumentException("Lake checkpoint entry budget exceeded");
        var result=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry:input.entrySet()) {
            var address=Objects.requireNonNull(entry.getKey());
            if (!HydrologySphericalTopologyV2.isCanonical(address))
                throw new IllegalArgumentException("Noncanonical lake checkpoint address");
            double value=Objects.requireNonNull(entry.getValue());
            number(value);
            result.put(address,value==0?0.0:value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static void unique(
            Map<HydrologyGlobalCatchmentsV2.Coordinate,
                GlobalHydrologyAddressV2.Sample> owners,
            GlobalHydrologyAddressV2.Sample address) {
        var previous=owners.putIfAbsent(
            HydrologyGlobalCatchmentsV2.coordinate(address),address);
        if (previous!=null)
            throw new IllegalArgumentException("Duplicate physical checkpoint vertex");
    }

    private static BigDecimal number(double value) {
        if (!Double.isFinite(value) || value<0)
            throw new IllegalArgumentException("Invalid lake checkpoint water");
        return BigDecimal.valueOf(value);
    }

    private static boolean close(BigDecimal a,BigDecimal b) {
        var tolerance=a.abs().max(b.abs()).multiply(BigDecimal.valueOf(1e-10))
            .max(BigDecimal.valueOf(1e-8));
        return a.subtract(b).abs().compareTo(tolerance)<=0;
    }
}
