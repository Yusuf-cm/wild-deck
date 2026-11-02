package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Opt-in, noncommitting seasonal lake operator for unresolved drainage
 * frontiers that are independently identified as physical lake inlets.
 * Each inlet consumes its pending runoff ONCE, updates a hypsometric lake,
 * and exports only its spill to an adjacent, still-pending frontier address.
 * The export is NOT re-injected into the same season's flow graph, avoiding
 * accidental double counting and hidden instantaneous lake cascades.
 *
 * This is an inflow-first operator split, not continuous lake hydraulics.
 * The caller must authenticate basin geometry, inlet identity, spill route,
 * climate forcing, and durable prior storage. No checkpoint is modified.
 */
public final class HydrologySeasonalLakeRoutingV2 {
    private HydrologySeasonalLakeRoutingV2() {}

    public record Lake(LakeReservoirV2.Basin basin, double previousVolumeM3,
                       double evaporationDepthMeters,
                       GlobalHydrologyAddressV2.Sample spillTo) {
        public Lake {
            Objects.requireNonNull(basin);
            Objects.requireNonNull(spillTo);
            if (!Double.isFinite(previousVolumeM3) || previousVolumeM3 < 0 ||
                previousVolumeM3 > basin.capacity() ||
                !Double.isFinite(evaporationDepthMeters) || evaporationDepthMeters < 0)
                throw new IllegalArgumentException("Invalid seasonal lake forcing or storage");
        }
    }

    public record Result(
            Map<GlobalHydrologyAddressV2.Sample,LakeReservoirV2.Step> lakes,
            Map<GlobalHydrologyAddressV2.Sample,Double> lakeInflowM3,
            Map<GlobalHydrologyAddressV2.Sample,Double> pendingM3,
            Map<GlobalHydrologyAddressV2.Sample,Double> pendingM3PerSecond,
            double previousLakeStorageM3, double finalLakeStorageM3,
            double lakeEvaporationM3, double verifiedOutletM3,
            double seasonalRunoffM3, double residualM3) {
        public Result {
            lakes = ordered(lakes);
            lakeInflowM3 = ordered(lakeInflowM3);
            pendingM3 = ordered(pendingM3);
            pendingM3PerSecond = ordered(pendingM3PerSecond);
            for (double value : new double[]{previousLakeStorageM3, finalLakeStorageM3,
                    lakeEvaporationM3, verifiedOutletM3, seasonalRunoffM3, residualM3})
                if (!Double.isFinite(value))
                    throw new IllegalArgumentException("Nonfinite seasonal lake budget");
        }
    }

    /**
     * Lake anchors must be unresolved frontier destinations, NOT already
     * verified ocean mouths. Spill is queued at a canonical adjacent vertex
     * for a subsequent routing/delivery operation, even when that vertex is
     * already represented in the original graph.
     */
    public static Result advance(HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,Lake> lakeForcing) {
        return advanceSubstepped(routed,lakeForcing,1);
    }

    /**
     * Opt-in uniform seasonal substeps. Lake spill remains a deferred frontier
     * export, not an instantaneous lake-to-lake cascade or a flood-wave model.
     */
    public static Result advanceSubstepped(HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,Lake> lakeForcing, int substeps) {
        Objects.requireNonNull(routed);
        Objects.requireNonNull(lakeForcing);
        if (substeps < 1 || substeps > HydrologySeasonalLakeIntegratorV2.MAX_SUBSTEPS)
            throw new IllegalArgumentException("Invalid lake substep count");
        var drainage = routed.drainage();
        var originalPending = drainage.pendingOutflow();
        var originalOutlets = drainage.terminalOutflow();
        var physicalOutputs = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        BigDecimal pendingTotal = BigDecimal.ZERO;
        BigDecimal outletTotal = BigDecimal.ZERO;
        for (var entry : originalPending.entrySet()) {
            validateOutput(entry,physicalOutputs);
            pendingTotal = pendingTotal.add(decimal(entry.getValue()));
        }
        for (var entry : originalOutlets.entrySet()) {
            validateOutput(entry,physicalOutputs);
            outletTotal = outletTotal.add(decimal(entry.getValue()));
        }
        double input = routed.budget().input();
        if (!Double.isFinite(input) || input < 0 ||
            !close(pendingTotal,decimal(routed.budget().frontier())) ||
            !close(outletTotal,decimal(routed.budget().outlet())) ||
            !close(decimal(input),pendingTotal.add(outletTotal)))
            throw new IllegalArgumentException("Unbalanced incoming seasonal routing");
        double seconds = routed.durationSeconds();
        if (!Double.isFinite(seconds) || seconds <= 0)
            throw new IllegalArgumentException("Invalid seasonal lake duration");
        verifyRates(originalPending,routed.frontierM3PerSecond(),seconds);
        verifyRates(originalOutlets,routed.outletM3PerSecond(),seconds);

        var orderedLakes = new TreeMap<GlobalHydrologyAddressV2.Sample,Lake>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        orderedLakes.putAll(lakeForcing);
        var physicalLakes = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var entry : orderedLakes.entrySet()) {
            var anchor = Objects.requireNonNull(entry.getKey());
            var lake = Objects.requireNonNull(entry.getValue());
            if (!HydrologySphericalTopologyV2.isCanonical(anchor) ||
                !physicalLakes.add(HydrologyGlobalCatchmentsV2.coordinate(anchor)) ||
                !originalPending.containsKey(anchor) ||
                !HydrologySphericalTopologyV2.isCanonical(lake.spillTo()) ||
                !HydrologySphericalTopologyV2.adjacent(anchor,lake.spillTo()))
                throw new IllegalArgumentException("Unowned lake inlet or invalid spill edge");
        }
        var physicalOutlets = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var mouth : originalOutlets.keySet())
            physicalOutlets.add(HydrologyGlobalCatchmentsV2.coordinate(mouth));
        var spillOwners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var pendingAddress : originalPending.keySet())
            spillOwners.put(HydrologyGlobalCatchmentsV2.coordinate(pendingAddress),pendingAddress);
        for (var lake : orderedLakes.values()) {
            var coordinate = HydrologyGlobalCatchmentsV2.coordinate(lake.spillTo());
            if (physicalLakes.contains(coordinate) || physicalOutlets.contains(coordinate))
                throw new IllegalArgumentException("Spill requires deferred nonlake frontier");
            var previousOwner = spillOwners.putIfAbsent(coordinate,lake.spillTo());
            if (previousOwner != null && !previousOwner.equals(lake.spillTo()))
                throw new IllegalArgumentException("Aliased physical spill frontier");
        }

        var pending = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        pending.putAll(originalPending);
        var steps = new TreeMap<GlobalHydrologyAddressV2.Sample,LakeReservoirV2.Step>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var inflows = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        BigDecimal previous = BigDecimal.ZERO, stored = BigDecimal.ZERO,
                   evaporated = BigDecimal.ZERO;
        for (var entry : orderedLakes.entrySet()) {
            var anchor = entry.getKey();
            var lake = entry.getValue();
            double incoming = pending.remove(anchor);
            var step = HydrologySeasonalLakeIntegratorV2.step(lake.basin(),
                lake.previousVolumeM3(),incoming,lake.evaporationDepthMeters(),substeps);
            previous = previous.add(decimal(lake.previousVolumeM3()));
            stored = stored.add(decimal(step.volume()));
            evaporated = evaporated.add(decimal(step.evaporated()));
            inflows.put(anchor,incoming);
            steps.put(anchor,step);
            if (step.overflow() > 0) {
                double combined = pending.getOrDefault(lake.spillTo(),0.0) + step.overflow();
                if (!Double.isFinite(combined))
                    throw new IllegalArgumentException("Lake spill discharge overflow");
                pending.put(lake.spillTo(),combined);
            }
        }
        BigDecimal remaining = BigDecimal.ZERO;
        var rates = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : pending.entrySet()) {
            remaining = remaining.add(decimal(entry.getValue()));
            double rate = entry.getValue()/seconds;
            if (!Double.isFinite(rate))
                throw new IllegalArgumentException("Lake spill rate overflow");
            rates.put(entry.getKey(),rate);
        }
        BigDecimal lhs = decimal(input).add(previous);
        BigDecimal rhs = outletTotal.add(remaining).add(stored).add(evaporated);
        if (!close(lhs,rhs))
            throw new IllegalArgumentException("Seasonal lake water conservation violated");
        return new Result(steps,inflows,pending,rates,
            finite(previous),finite(stored),finite(evaporated),
            finite(outletTotal),input,finite(lhs.subtract(rhs)));
    }

    private static void verifyRates(
            Map<GlobalHydrologyAddressV2.Sample,Double> volumes,
            Map<GlobalHydrologyAddressV2.Sample,Double> rates, double seconds) {
        Objects.requireNonNull(rates);
        if (!volumes.keySet().equals(rates.keySet()))
            throw new IllegalArgumentException("Seasonal lake rate addresses mismatch");
        for (var entry : volumes.entrySet()) {
            var rate = decimal(rates.get(entry.getKey()));
            var recoveredVolume = rate.multiply(BigDecimal.valueOf(seconds));
            if (!close(recoveredVolume,decimal(entry.getValue())))
                throw new IllegalArgumentException("Seasonal lake discharge rate mismatch");
        }
    }

    private static void validateOutput(
            Map.Entry<GlobalHydrologyAddressV2.Sample,Double> entry,
            Set<HydrologyGlobalCatchmentsV2.Coordinate> physical) {
        var address = Objects.requireNonNull(entry.getKey());
        if (!HydrologySphericalTopologyV2.isCanonical(address) ||
            !physical.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
            throw new IllegalArgumentException("Duplicate physical seasonal outlet");
        decimal(entry.getValue());
    }

    private static BigDecimal decimal(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid seasonal water volume");
        return BigDecimal.valueOf(value);
    }

    private static boolean close(BigDecimal a,BigDecimal b) {
        var tolerance = a.abs().max(b.abs())
            .multiply(BigDecimal.valueOf(1e-10))
            .max(BigDecimal.valueOf(1e-8));
        return a.subtract(b).abs().compareTo(tolerance) <= 0;
    }

    private static double finite(BigDecimal value) {
        double number = value.doubleValue();
        if (!Double.isFinite(number))
            throw new IllegalArgumentException("Seasonal lake budget overflow");
        return number;
    }

    private static <T> Map<GlobalHydrologyAddressV2.Sample,T> ordered(
            Map<GlobalHydrologyAddressV2.Sample,T> source) {
        var result = new TreeMap<GlobalHydrologyAddressV2.Sample,T>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        result.putAll(Objects.requireNonNull(source));
        return Collections.unmodifiableMap(result);
    }
}
