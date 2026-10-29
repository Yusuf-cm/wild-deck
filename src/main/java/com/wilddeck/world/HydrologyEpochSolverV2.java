package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic orchestration for a set of loaded/unloaded-region flow nodes.
 * Reconciles shared vertices, solves routes and exports unresolved frontier
 * discharge. An explicit incoming map represents water from an earlier
 * completed epoch, not another pass of the same epoch's graph.
 */
public final class HydrologyEpochSolverV2 {
    private HydrologyEpochSolverV2(){}

    public static HydrologyFlowGraphV2.Result solve(
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> mosaics,
            Map<GlobalHydrologyAddressV2.Sample,Double> previousEpochIncoming) {
        Objects.requireNonNull(previousEpochIncoming);
        var merged=HydrologyMosaicJoinV2.merge(mosaics);
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>(merged.size());
        var known=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:merged)known.add(node.address());
        for(var entry:previousEpochIncoming.entrySet()){
            Objects.requireNonNull(entry.getKey());
            if(!Double.isFinite(entry.getValue())||entry.getValue()<0)
                throw new IllegalArgumentException("Invalid incoming water");
            if(!known.contains(entry.getKey()))
                throw new IllegalArgumentException("Incoming address not loaded");
        }
        for(var node:merged){
            double additional=previousEpochIncoming.getOrDefault(node.address(),0.0);
            double water=node.localWater()+additional;
            if(!Double.isFinite(water))throw new IllegalArgumentException("Water overflow");
            nodes.add(new HydrologyFlowGraphV2.Node(
                node.address(),water,node.downstream(),node.terminal()));
        }
        return HydrologyFlowGraphV2.solve(nodes);
    }
}
