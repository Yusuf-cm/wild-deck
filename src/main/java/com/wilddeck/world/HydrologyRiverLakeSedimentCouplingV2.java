package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Opt-in bridge from river erosion mass to lake sediment infill and the
 * existing conservative river/lake water step. The routing graph and lake
 * geometry must already be verified. Lake interception uses the explicit
 * idealization that all suspended sediment entering a lake settles there.
 *
 * River-bed deposition and terminal sediment export are returned for later
 * terrain/persistence handling; they are not silently applied to the world.
 * The coupled step refuses unresolved sediment frontiers and completely
 * silted lakes rather than inventing downstream routes.
 */
public final class HydrologyRiverLakeSedimentCouplingV2 {
    private HydrologyRiverLakeSedimentCouplingV2() {}

    public record Budget(double eroded, double riverDeposited,
                         double lakeDeposited, double lakeExported,
                         double terminalExported, double residual) {}

    public record Result(HydrologyRiverSedimentTransportV2.Result river,
                         HydrologyLakeSedimentNetworkV2.Result lake,
                         Budget budget) {
        public Result {
            Objects.requireNonNull(river);
            Objects.requireNonNull(lake);
            Objects.requireNonNull(budget);
        }
    }

    public static Result step(HydrologySphericalLakeBasinsV2.Plan plan,
            HydrologyLakeStateV2.State previous,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            Map<GlobalHydrologyAddressV2.Sample,Double> capacity,
            double dryBulkDensity,
            SeasonalLakeEvolutionV2.EvaporationMode mode) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(water);
        Objects.requireNonNull(mode);
        if (!Double.isFinite(dryBulkDensity) || dryBulkDensity <= 0)
            throw new IllegalArgumentException("Invalid lake sediment bulk density");
        var river = HydrologyRiverSedimentTransportV2.route(plan,eroded,capacity);
        if (!river.pendingExport().isEmpty())
            throw new IllegalArgumentException("Unresolved river sediment frontier");
        var forcing = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyLakeSedimentNetworkV2.Sediment>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var lake : plan.lakes()) {
            if (forcing.putIfAbsent(lake.anchor(),
                    new HydrologyLakeSedimentNetworkV2.Sediment(
                        river.lakeCaptured().getOrDefault(lake.anchor(),0.0),
                        dryBulkDensity)) != null)
                throw new IllegalArgumentException("Duplicate lake sediment forcing");
        }
        var advanced = HydrologyLakeSedimentNetworkV2.step(
            plan,previous,water,forcing,mode);
        var rb = river.budget();
        var lb = advanced.budget();
        BigDecimal input = BigDecimal.valueOf(rb.eroded());
        BigDecimal accounted = BigDecimal.valueOf(rb.riverDeposited())
            .add(BigDecimal.valueOf(rb.terminalExported()))
            .add(BigDecimal.valueOf(lb.sedimentDeposited()))
            .add(BigDecimal.valueOf(lb.sedimentExported()));
        double residual = input.subtract(accounted).doubleValue();
        double tolerance = Math.max(1e-8, Math.abs(rb.eroded())*1e-10);
        if (!Double.isFinite(residual) || Math.abs(residual) > tolerance)
            throw new IllegalArgumentException("Coupled sediment conservation failure");
        return new Result(river,advanced,new Budget(rb.eroded(),
            rb.riverDeposited(),lb.sedimentDeposited(),
            lb.sedimentExported(),rb.terminalExported(),residual));
    }
}
