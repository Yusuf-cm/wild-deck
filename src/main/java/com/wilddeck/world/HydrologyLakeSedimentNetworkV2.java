package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Opt-in sediment-first coupling for an already verified closed-sphere lake
 * network. Sediment reduces reservoir capacity before the seasonal water
 * routing step. Water displaced from the old storage is reintroduced ONCE
 * as lake inflow, so it can evaporate, remain stored or spill to downstream
 * lakes and verified terminal outlets through the existing network solver.
 *
 * This returns changed geometry for explicit persistence; it does not mutate
 * authoritative terrain or discover a new spillway. Complete silting fails
 * closed because the old lake graph is no longer physically valid.
 * Exported sediment is reported but NOT routed automatically.
 */
public final class HydrologyLakeSedimentNetworkV2 {
    private HydrologyLakeSedimentNetworkV2() {}

    public record Sediment(double mass, double dryBulkDensity) {
        public Sediment {
            if (!Double.isFinite(mass) || mass < 0 ||
                !Double.isFinite(dryBulkDensity) || dryBulkDensity <= 0)
                throw new IllegalArgumentException("Invalid sediment forcing");
        }
    }

    public record Budget(double initialStorage, double sourceWater,
                         double externalInflow, double evaporation,
                         double terminalOutflow, double finalStorage,
                         double waterResidual, double sedimentInput,
                         double sedimentDeposited, double sedimentExported,
                         double sedimentResidual) {}

    public record Result(HydrologySphericalLakeBasinsV2.Plan updatedPlan,
                         HydrologyLakeNetworkStepV2.Result water,
                         Map<GlobalHydrologyAddressV2.Sample,Double> displaced,
                         Map<GlobalHydrologyAddressV2.Sample,Double> deposited,
                         Map<GlobalHydrologyAddressV2.Sample,Double> exported,
                         Budget budget) {
        public Result {
            Objects.requireNonNull(updatedPlan);
            Objects.requireNonNull(water);
            displaced = sorted(displaced);
            deposited = sorted(deposited);
            exported = sorted(exported);
            Objects.requireNonNull(budget);
        }
    }

    public static Result step(HydrologySphericalLakeBasinsV2.Plan plan,
            HydrologyLakeStateV2.State previous,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Sediment> sediment,
            SeasonalLakeEvolutionV2.EvaporationMode mode) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(water);
        Objects.requireNonNull(sediment);
        Objects.requireNonNull(mode);
        var lakes = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologySphericalLakeBasinsV2.Lake>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var lake : plan.lakes()) {
            Objects.requireNonNull(lake);
            if (lakes.putIfAbsent(lake.anchor(), lake) != null)
                throw new IllegalArgumentException("Duplicate lake anchor");
        }
        if (!lakes.keySet().equals(water.keySet()) ||
            !lakes.keySet().equals(sediment.keySet()))
            throw new IllegalArgumentException("Forcing must cover every lake exactly once");
        var updatedLakes = new ArrayList<HydrologySphericalLakeBasinsV2.Lake>();
        var updatedVolumes = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var updatedForcing = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyLakeNetworkStepV2.Forcing>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        var displaced = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var deposited = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var exported = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        BigDecimal initialStorage = BigDecimal.ZERO;
        BigDecimal external = BigDecimal.ZERO;
        BigDecimal sedimentIn = BigDecimal.ZERO;
        BigDecimal sedimentOut = BigDecimal.ZERO;
        BigDecimal sedimentKept = BigDecimal.ZERO;
        for (var lake : lakes.values()) {
            var anchor = lake.anchor();
            var s = Objects.requireNonNull(sediment.get(anchor));
            var f = Objects.requireNonNull(water.get(anchor));
            double stored = previous.volumes().getOrDefault(anchor, 0.0);
            var infill = LakeSedimentEvolutionV2.deposit(
                lake.reservoir(), stored, s.mass(), s.dryBulkDensity());
            // Fully silted lakes require a fresh verified routing topology.
            // Reusing their old lake spill edges would invent a reservoir.
            var newBasin = infill.basin().orElseThrow(() ->
                new IllegalArgumentException("Lake fully silted: reverify drainage topology"));
            double inflow = f.additionalInflow() + infill.displacedWater();
            if (!Double.isFinite(inflow))
                throw new IllegalArgumentException("Displaced water overflow");
            updatedLakes.add(new HydrologySphericalLakeBasinsV2.Lake(
                anchor, lake.cells(), lake.spillTarget(), lake.confirmedOutlet(), newBasin));
            updatedVolumes.put(anchor, infill.storedWater());
            updatedForcing.put(anchor, new HydrologyLakeNetworkStepV2.Forcing(
                inflow, f.evaporationDepth(), f.substeps()));
            displaced.put(anchor, infill.displacedWater());
            deposited.put(anchor, infill.depositedSediment());
            exported.put(anchor, infill.exportedSediment());
            initialStorage = initialStorage.add(decimal(stored));
            external = external.add(decimal(f.additionalInflow()));
            sedimentIn = sedimentIn.add(decimal(s.mass()));
            sedimentKept = sedimentKept.add(decimal(infill.depositedSediment()));
            sedimentOut = sedimentOut.add(decimal(infill.exportedSediment()));
        }
        var updatedPlan = new HydrologySphericalLakeBasinsV2.Plan(
            plan.routes(), updatedLakes);
        var routed = HydrologyLakeNetworkStepV2.step(updatedPlan,
            new HydrologyLakeStateV2.State(previous.world(), updatedVolumes),
            updatedForcing, mode);
        var b = routed.budget();
        BigDecimal left = initialStorage.add(decimal(b.sourceWater())).add(external);
        BigDecimal right = decimal(b.evaporation()).add(decimal(b.terminalOutflow()))
            .add(decimal(b.finalStorage()));
        double waterResidual = left.subtract(right).doubleValue();
        double sedimentResidual = sedimentIn.subtract(sedimentKept)
            .subtract(sedimentOut).doubleValue();
        verify(left, right, waterResidual, "Coupled water budget violated");
        verify(sedimentIn, sedimentKept.add(sedimentOut), sedimentResidual,
            "Coupled sediment budget violated");
        return new Result(updatedPlan, routed, displaced, deposited, exported,
            new Budget(initialStorage.doubleValue(), b.sourceWater(),
                external.doubleValue(), b.evaporation(), b.terminalOutflow(),
                b.finalStorage(), waterResidual, sedimentIn.doubleValue(),
                sedimentKept.doubleValue(), sedimentOut.doubleValue(), sedimentResidual));
    }

    private static BigDecimal decimal(double value) {
        if (!Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid coupled budget");
        return BigDecimal.valueOf(value);
    }
    private static void verify(BigDecimal left, BigDecimal right,
                               double residual, String message) {
        double scale = Math.max(left.doubleValue(), right.doubleValue());
        double tolerance = Math.max(1e-8, scale * 1e-10);
        if (!Double.isFinite(scale) || !Double.isFinite(residual) ||
            Math.abs(residual) > tolerance)
            throw new IllegalArgumentException(message);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> sorted(
            Map<GlobalHydrologyAddressV2.Sample,Double> source) {
        var map = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        map.putAll(source);
        return Collections.unmodifiableMap(map);
    }
}
