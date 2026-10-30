package com.wilddeck.world;

import java.io.IOException;
import java.util.*;

/**
 * Opt-in, single-writer seasonal snow/soil -> river -> lake epoch.
 *
 * Snow/soil runoff is the ONLY locally generated water in the supplied
 * zero-runoff spherical plan. Incoming durable river receipts are added by
 * the lake-aware runner before lake interception. Both seasonal stores and
 * lake volumes are written in one checkpoint, or neither is changed.
 *
 * Requires an already verified complete single-resolution spherical route
 * plan and caller-supplied seasonal forcing. This is not ocean discovery,
 * distributed persistence, or a climate-calibrated water cycle.
 */
public final class HydrologySeasonalLakeNetworkV2 {
    private HydrologySeasonalLakeNetworkV2() {}

    public record Budget(double initialSnowSoil, double initialLakes,
                         double precipitation, double receivedRiverWater,
                         double snowSoilEvaporation, double lakeEvaporation,
                         double terminalDischarge, double finalSnowSoil,
                         double finalLakes, double residual) {}

    public record Advanced(HydrologySnowSoilStateV2.Step snowSoil,
                           HydrologyLakeNetworkStepV2.Result lakes,
                           Budget budget) {
        public Advanced {
            Objects.requireNonNull(snowSoil);
            Objects.requireNonNull(lakes);
            Objects.requireNonNull(budget);
        }
    }

    /** Explicitly initialize nested state; legacy payloads are never migrated implicitly. */
    public static String initialState(String world) {
        var snow=new HydrologySnowSoilStateV2.State(
            Objects.requireNonNull(world),Map.of());
        return HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State(
            HydrologySnowSoilStateV2.encode(snow),Map.of()));
    }

    public static Advanced advance(HydrologyWorldRunnerV2 runner,
            String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> lakeForcing)
            throws IOException {
        return advanceInternal(runner,nextWorld,plan,weather,lakeForcing,null);
    }

    /**
     * Resume seasonal forcing after a quiescent, geometry-bound drainage
     * transition. Both authoritative atlas and lake plan are reverified;
     * the durable topology/geometry identities remain bound across epochs.
     */
    public static Advanced advanceBound(HydrologyWorldRunnerV2 runner,
            String nextWorld,
            HydrologySphericalRouteAtlasV2 atlas,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> lakeForcing)
            throws IOException {
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(runner);
        runner.verifyBoundWetLakeDrainage(atlas,plan);
        return advanceInternal(runner,nextWorld,plan,weather,lakeForcing,atlas);
    }

    private static Advanced advanceInternal(HydrologyWorldRunnerV2 runner,
            String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> lakeForcing,
            HydrologySphericalRouteAtlasV2 boundAtlas)
            throws IOException {
        Objects.requireNonNull(runner);
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(weather);
        Objects.requireNonNull(lakeForcing);

        var previousLakes=HydrologyLakeStateV2.decode(
            boundAtlas==null ? runner.worldState() :
            HydrologyDrainageActivationV2.decode(runner.worldState()).lakeState());
        var previousSnow=HydrologySnowSoilStateV2.decode(previousLakes.world());
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:plan.routes()) {
            Objects.requireNonNull(node);
            if(!HydrologySphericalTopologyV2.isCanonical(node.address()) ||
               node.localWater()!=0.0 ||
               nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException(
                    "Seasonal lake plan needs unique canonical zero-runoff vertices");
        }
        if(nodes.isEmpty() || !nodes.keySet().containsAll(weather.keySet()))
            throw new IllegalArgumentException("Weather outside verified planet");
        // Reject independently supplied lake inflow: this adapter owns all
        // local precipitation-derived runoff. External transfers must arrive
        // through the runner's durable receipt inbox.
        for(var forcing:lakeForcing.values()) {
            Objects.requireNonNull(forcing);
            if(forcing.additionalInflow()!=0.0)
                throw new IllegalArgumentException(
                    "Additional lake inflow must use durable river receipts");
        }
        var snow=HydrologySnowSoilStateV2.advance(previousSnow,weather);
        var runoff=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var source:snow.runoff()) {
            if(runoff.putIfAbsent(source.address(),source.localWater())!=null)
                throw new IllegalArgumentException("Duplicate seasonal runoff owner");
        }
        var enriched=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:nodes.values())
            enriched.add(new HydrologyFlowGraphV2.Node(node.address(),
                runoff.getOrDefault(node.address(),0.0),
                node.downstream(),node.terminal()));

        var nextSnow=new HydrologySnowSoilStateV2.State(
            nextWorld,snow.state().cells());
        // Validate the combined snow/soil + river + lake budget while
        // receipts and world state are still staged, not after disk commit.
        final Budget[] checked=new Budget[1];
        var runoffPlan=new HydrologySphericalLakeBasinsV2.Plan(
            enriched,plan.lakes());
        var validator=(java.util.function.Consumer<HydrologyLakeNetworkStepV2.Result>)
            result->checked[0]=verifyCombinedBudget(snow,result);
        var lakes=boundAtlas==null ?
            runner.advanceClosedSphericalLakeNetworkValidated(
                HydrologySnowSoilStateV2.encode(nextSnow),
                runoffPlan,lakeForcing,validator) :
            runner.advanceBoundClosedSphericalLakeNetworkValidated(
                HydrologySnowSoilStateV2.encode(nextSnow),
                boundAtlas,runoffPlan,lakeForcing,validator);
        return new Advanced(snow,lakes,checked[0]);
    }

    private static Budget verifyCombinedBudget(
            HydrologySnowSoilStateV2.Step snow,
            HydrologyLakeNetworkStepV2.Result lakes) {
        double receipts=lakes.budget().sourceWater()-snow.runoffVolume();
        double initialLakes=lakes.budget().previousStorage();
        double finalLakes=lakes.budget().finalStorage();
        double terminal=lakes.budget().terminalOutflow();
        double lakeEvap=lakes.budget().evaporation();
        double left=snow.initialVolume()+initialLakes+
            snow.precipitationVolume()+receipts;
        double right=snow.evaporationVolume()+lakeEvap+terminal+
            snow.storedVolume()+finalLakes;
        double residual=left-right;
        double tolerance=Math.max(1e-8,Math.max(left,right)*1e-10);
        if(!Double.isFinite(receipts) || receipts < -tolerance ||
           !Double.isFinite(left) || !Double.isFinite(right) ||
           !Double.isFinite(residual) || Math.abs(residual)>tolerance)
            throw new IllegalStateException("Combined seasonal water budget violated");
        return new Budget(snow.initialVolume(),
            initialLakes,snow.precipitationVolume(),Math.max(0,receipts),
            snow.evaporationVolume(),lakeEvap,terminal,
            snow.storedVolume(),finalLakes,residual);
    }
}
