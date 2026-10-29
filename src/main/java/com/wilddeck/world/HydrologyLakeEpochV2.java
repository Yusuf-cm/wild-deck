package com.wilddeck.world;

import java.io.IOException;
import java.util.*;

/**
 * Opt-in single-writer integration of persistent lake volumes with a routed
 * hydrology epoch. Each lake's seasonal spill is injected ONCE into a loaded
 * outlet vertex and committed atomically with the water inbox and lake state.
 *
 * Callers must supply stable, physically verified basin anchors, geometry and
 * outlet addresses. Catchment runoff supplied to a lake must NOT also appear
 * as graph localWater, or water would be counted twice. Unknown or unloaded
 * lake outlets fail closed. Basin geometry changes require explicit migration.
 */
public final class HydrologyLakeEpochV2 {
    private HydrologyLakeEpochV2(){}

    public record LakePeriod(GlobalHydrologyAddressV2.Sample anchor,
                             GlobalHydrologyAddressV2.Sample outlet,
                             LakeReservoirV2.Basin basin,
                             double contributingArea,
                             SeasonalLakeCycleV2.Period period) {
        public LakePeriod {
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(outlet);
            Objects.requireNonNull(basin);
            Objects.requireNonNull(period);
            if(anchor.level()!=outlet.level()||
               anchor.divisions()!=outlet.divisions()||
               !Double.isFinite(contributingArea)||contributingArea<0)
                throw new IllegalArgumentException("Invalid lake epoch geometry");
        }
    }

    public record Advanced(HydrologyDurableEpochV2.Routed routed,
                           Map<GlobalHydrologyAddressV2.Sample,SeasonalLakeCycleV2.Season> lakes) {
        public Advanced {
            Objects.requireNonNull(routed);
            var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,SeasonalLakeCycleV2.Season>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            ordered.putAll(lakes);
            lakes=Collections.unmodifiableMap(ordered);
        }
    }

    public static Advanced advance(HydrologyWorldRunnerV2 runner,String nextWorld,
            Collection<HydrologyFlowGraphV2.Node> loaded,
            Collection<LakePeriod> periods,int substeps) throws IOException {
        Objects.requireNonNull(runner);
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(periods);
        if(substeps<1||substeps>4096)
            throw new IllegalArgumentException("Invalid lake substeps");
        var previous=HydrologyLakeStateV2.decode(runner.worldState());
        var volumes=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        volumes.putAll(previous.volumes());
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:loaded){
            Objects.requireNonNull(node);
            if(nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException("Duplicate graph vertex");
        }
        var seen=new HashSet<GlobalHydrologyAddressV2.Sample>();
        var steps=new TreeMap<GlobalHydrologyAddressV2.Sample,SeasonalLakeCycleV2.Season>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var spill=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var lake:periods){
            Objects.requireNonNull(lake);
            if(!seen.add(lake.anchor()))
                throw new IllegalArgumentException("Duplicate lake anchor");
            if(!nodes.containsKey(lake.outlet()))
                throw new IllegalArgumentException("Lake outlet is not loaded");
            double initial=volumes.getOrDefault(lake.anchor(),0.0);
            var cycle=SeasonalLakeCycleV2.simulateSubsteps(lake.basin(),initial,
                lake.contributingArea(),List.of(lake.period()),substeps);
            var step=cycle.seasons().get(0);
            volumes.put(lake.anchor(),cycle.finalVolume());
            steps.put(lake.anchor(),step);
            spill.merge(lake.outlet(),cycle.totalOverflow(),(a,b)->{
                double total=a+b;
                if(!Double.isFinite(total))
                    throw new IllegalArgumentException("Lake spill overflow");
                return total;
            });
        }
        var routedNodes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:nodes.values()){
            double total=node.localWater()+spill.getOrDefault(node.address(),0.0);
            if(!Double.isFinite(total))
                throw new IllegalArgumentException("Lake outlet discharge overflow");
            routedNodes.add(new HydrologyFlowGraphV2.Node(
                node.address(),total,node.downstream(),node.terminal()));
        }
        var state=HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State(nextWorld,volumes));
        var routed=runner.advanceAndQueueKnownFrontier(state,List.of(routedNodes));
        return new Advanced(routed,steps);
    }
}
