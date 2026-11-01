package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic multi-season sediment/lake continuation on an unchanged,
 * verified drainage topology. Runoff is explicitly replaced each season,
 * never added to last season's source water. Changed lake beds and stored
 * water carry forward, while displaced water traverses downstream lakes.
 *
 * This is an in-memory, caller-forced prototype: it neither commits geometry
 * to authoritative terrain nor migrates routes after erosion. Complete
 * silting fails closed until a new verified topology is supplied.
 */
public final class HydrologyLakeSedimentSeasonsV2 {
    private HydrologyLakeSedimentSeasonsV2() {}

    public record Season(
            Map<GlobalHydrologyAddressV2.Sample,Double> localRunoff,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeNetworkStepV2.Forcing> water,
            Map<GlobalHydrologyAddressV2.Sample,HydrologyLakeSedimentNetworkV2.Sediment> sediment) {
        public Season {
            Objects.requireNonNull(localRunoff);
            Objects.requireNonNull(water);
            Objects.requireNonNull(sediment);
            localRunoff=Map.copyOf(localRunoff);
            water=Map.copyOf(water);
            sediment=Map.copyOf(sediment);
        }
    }

    /**
     * Resume from a canonical geometry+water payload and return a new payload
     * for atomic storage by the caller. The baseline must be the same original
     * lake/river geometry used when the snapshot was first created.
     *
     * No filesystem write occurs here; callers must commit the new payload
     * and any external transfer receipts together in their own transaction.
     */
    public record Continuation(List<HydrologyLakeSedimentNetworkV2.Result> steps,
                               String snapshot) {
        public Continuation {
            steps = List.copyOf(steps);
            Objects.requireNonNull(snapshot);
        }
    }

    public static Continuation resume(
            HydrologySphericalLakeBasinsV2.Plan baseline,
            String snapshot,
            List<Season> seasons,
            SeasonalLakeEvolutionV2.EvaporationMode mode) {
        var restored = HydrologyLakeSedimentSnapshotV2.decode(baseline, snapshot);
        var steps = simulate(restored.plan(), restored.water(), seasons, mode);
        if (steps.isEmpty()) return new Continuation(steps, snapshot);
        var last = steps.get(steps.size() - 1);
        return new Continuation(steps,
            HydrologyLakeSedimentSnapshotV2.encode(
                baseline, last.updatedPlan(), last.water().state()));
    }

    public static List<HydrologyLakeSedimentNetworkV2.Result> simulate(
            HydrologySphericalLakeBasinsV2.Plan zeroRunoffPlan,
            HydrologyLakeStateV2.State initial,
            List<Season> seasons,
            SeasonalLakeEvolutionV2.EvaporationMode mode) {
        Objects.requireNonNull(zeroRunoffPlan);
        Objects.requireNonNull(initial);
        Objects.requireNonNull(seasons);
        Objects.requireNonNull(mode);
        var topology=new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:zeroRunoffPlan.routes()) {
            Objects.requireNonNull(node);
            if(node.localWater()!=0 || topology.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException(
                    "Seasonal topology needs unique zero-runoff source vertices");
        }
        if(topology.isEmpty())
            throw new IllegalArgumentException("Empty seasonal drainage topology");
        var plan=zeroRunoffPlan;
        var state=initial;
        var result=new ArrayList<HydrologyLakeSedimentNetworkV2.Result>();
        for(var season:seasons) {
            Objects.requireNonNull(season);
            if(!topology.keySet().containsAll(season.localRunoff().keySet()))
                throw new IllegalArgumentException("Seasonal runoff outside verified topology");
            for(var volume:season.localRunoff().values())
                if(volume==null || !Double.isFinite(volume) || volume<0)
                    throw new IllegalArgumentException("Invalid seasonal runoff");
            var routes=new ArrayList<HydrologyFlowGraphV2.Node>(topology.size());
            for(var node:topology.values())
                routes.add(new HydrologyFlowGraphV2.Node(node.address(),
                    season.localRunoff().getOrDefault(node.address(),0.0),
                    node.downstream(),node.terminal()));
            var advanced=HydrologyLakeSedimentNetworkV2.step(
                new HydrologySphericalLakeBasinsV2.Plan(routes,plan.lakes()),
                state,season.water(),season.sediment(),mode);
            result.add(advanced);
            plan=advanced.updatedPlan();
            state=advanced.water().state();
        }
        return List.copyOf(result);
    }
}
