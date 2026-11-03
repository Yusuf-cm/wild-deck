package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in one-checkpoint seasonal water transition: previously conserved
 * frontier water, durable boundary receipts, verified ocean discharge,
 * scheduled/uniform lake forcing and the cumulative outlet ledger advance
 * together. This deliberately does not advance snow/soil or sediment state.
 *
 * Mouths, drainage edges, loaded vertices and seasonal runoff must be supplied
 * and authenticated by the caller. This is a single-writer local checkpoint,
 * not a distributed transaction or an authoritative planetary world tick.
 */
public final class HydrologySeasonalWaterEpochV2 {
    private HydrologySeasonalWaterEpochV2() {}

    public record Prepared(HydrologySeasonalOutletSettlementV2.State state,
            HydrologyCheckpointV2.Checkpoint checkpoint,
            HydrologySeasonalPendingOutletV2.Result oldWaterRouting,
            HydrologySeasonalLaggedLakeNetworkV2.CarryoverResult lakeStep,
            Map<GlobalHydrologyAddressV2.Sample,Double> claimedM3,
            Map<GlobalHydrologyAddressV2.Sample,Double> newOceanDischargeM3) {
        public Prepared {
            Objects.requireNonNull(state);
            Objects.requireNonNull(checkpoint);
            Objects.requireNonNull(oldWaterRouting);
            Objects.requireNonNull(lakeStep);
            claimedM3=sorted(claimedM3);
            newOceanDischargeM3=sorted(newOceanDischargeM3);
        }
    }

    /** Advance with uniform seasonal forcing, without changing persistent state. */
    public static Prepared prepareUniform(HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        return prepare(expected,runoff,lakes,slices,null,downstream,loaded,verifiedMouths);
    }

    /** Advance with chronological local rainfall and evaporation intervals. */
    public static Prepared prepareScheduled(HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        return prepare(expected,runoff,lakes,0,Objects.requireNonNull(schedules),
            downstream,loaded,verifiedMouths);
    }

    private static Prepared prepare(HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(runoff);
        Objects.requireNonNull(lakes);
        Objects.requireNonNull(downstream);
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(verifiedMouths);
        var previous=HydrologySeasonalOutletSettlementV2.decode(expected.worldState());
        var old=previous.lakes();
        if (old.season()!=expected.epoch())
            throw new IllegalArgumentException("Stale seasonal water epoch");
        if (!old.lakeStorageM3().keySet().equals(lakes.keySet()) ||
            !loaded.containsAll(lakes.keySet()))
            throw new IllegalArgumentException("Unknown or unloaded lake in seasonal epoch");
        if (!verifiedMouths.containsAll(runoff.drainage().terminalOutflow().keySet()) ||
            !loaded.containsAll(runoff.drainage().terminalOutflow().keySet()))
            throw new IllegalArgumentException("Seasonal outlet lacks loaded mouth authority");

        // Claiming the restored inbox changes only an in-memory copy. Its
        // delivery markers become durable with the new water checkpoint.
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        var claimed=inbox.claimAvailable(old.season(),loaded);
        var carried=HydrologySeasonalLakeInboxCommitV2.combined(old.pendingM3(),claimed);
        var oldRouting=HydrologySeasonalPendingOutletV2.route(
            carried,downstream,loaded,lakes.keySet(),verifiedMouths);
        var candidate=schedules==null ?
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
                runoff,lakes,slices,oldRouting.pendingM3()) :
            HydrologySeasonalLaggedLakeNetworkV2.advanceScheduledWithCarryover(
                runoff,lakes,schedules,oldRouting.pendingM3());

        // The lake operator sees only unresolved carryover, not water that
        // has already reached an authenticated mouth in this same epoch.
        var augmented=new HydrologySeasonalLakeCarryoverStateV2.State(
            old.world(),old.season(),old.lakeStorageM3(),oldRouting.pendingM3());
        var nextLakes=HydrologySeasonalLakeCarryoverStateV2.advance(
            augmented,lakes,candidate);
        var delta=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry:oldRouting.verifiedOutletM3().entrySet())
            add(delta,entry.getKey(),entry.getValue());
        for (var entry:runoff.drainage().terminalOutflow().entrySet())
            add(delta,entry.getKey(),entry.getValue());

        // Independent end-to-end conservation, including claimed receipts
        // and old water discharged before the lake step.
        BigDecimal input=sum(old.pendingM3()).add(sum(claimed))
            .add(sum(old.lakeStorageM3()))
            .add(number(candidate.result().seasonalRunoffM3()));
        BigDecimal output=sum(nextLakes.pendingM3())
            .add(sum(nextLakes.lakeStorageM3()))
            .add(number(candidate.result().lakeEvaporationM3()))
            .add(sum(delta));
        if (!close(input,output) ||
            !close(sum(runoff.drainage().terminalOutflow()),
                number(candidate.result().verifiedOutletM3())))
            throw new IllegalArgumentException("Seasonal ocean/lake/receipt budget mismatch");

        var ledger=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        ledger.putAll(previous.dischargedM3());
        for (var entry:delta.entrySet()) {
            double prior=ledger.getOrDefault(entry.getKey(),0.0);
            // Both historical ocean discharge and the new epoch's outflow
            // must remain representable; neither may disappear on addition.
            double next=HydrologyWaterVolumeAdditionV2.add(prior,entry.getValue());
            ledger.put(entry.getKey(),next);
        }
        var next=new HydrologySeasonalOutletSettlementV2.State(nextLakes,ledger);
        var checkpoint=HydrologyCheckpointV2.capture(
            nextLakes.season(),HydrologySeasonalOutletSettlementV2.encode(next),inbox);
        return new Prepared(next,checkpoint,oldRouting,candidate,claimed,delta);
    }

    public static Prepared commitUniform(Path file,HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            int slices,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) throws IOException {
        var prepared=prepareUniform(expected,runoff,lakes,slices,downstream,loaded,verifiedMouths);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }

    public static Prepared commitScheduled(Path file,HydrologyCheckpointV2.Checkpoint expected,
            HydrologySeasonalGlobalRoutingV2.Result runoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> downstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) throws IOException {
        var prepared=prepareScheduled(expected,runoff,lakes,schedules,downstream,loaded,verifiedMouths);
        HydrologyCheckpointStoreV2.saveNext(file,expected,prepared.checkpoint());
        return prepared;
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> sorted(
            Map<GlobalHydrologyAddressV2.Sample,Double> values) {
        var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        ordered.putAll(Objects.requireNonNull(values));
        return Collections.unmodifiableMap(ordered);
    }

    private static void add(Map<GlobalHydrologyAddressV2.Sample,Double> values,
            GlobalHydrologyAddressV2.Sample at,double volume) {
        if (!Double.isFinite(volume) || volume<0)
            throw new IllegalArgumentException("Invalid ocean discharge");
        // Old frontier water and new seasonal runoff can reach the same
        // ocean mouth. Losing either contribution would corrupt the budget.
        double next=HydrologyWaterVolumeAdditionV2.add(
            values.getOrDefault(at,0.0),volume);
        values.put(at,next);
    }

    private static BigDecimal number(double value) {
        if (!Double.isFinite(value) || value<0)
            throw new IllegalArgumentException("Invalid seasonal water");
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal sum(Map<GlobalHydrologyAddressV2.Sample,Double> values) {
        BigDecimal total=BigDecimal.ZERO;
        for (double value:values.values()) total=total.add(number(value));
        return total;
    }

    private static boolean close(BigDecimal a,BigDecimal b) {
        var tolerance=a.abs().max(b.abs()).multiply(BigDecimal.valueOf(1e-10))
            .max(BigDecimal.valueOf(1e-8));
        return a.subtract(b).abs().compareTo(tolerance)<=0;
    }
}
