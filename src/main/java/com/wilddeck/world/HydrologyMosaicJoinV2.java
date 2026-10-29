package com.wilddeck.world;

import java.util.*;

/**
 * Reconciles overlapping shared samples from adjacent finite mosaics.
 * Identical local rainfall at a shared vertex is counted ONCE, never summed.
 * A known route may replace an unresolved boundary route. Conflicting known
 * routes or different positive rainfall values are rejected, not guessed.
 *
 * The merged graph is deterministic for any input mosaic ordering. A merge
 * cannot repair genuinely inconsistent local spill geometry; those mosaics
 * must be regenerated as one connected hydrology solve.
 */
public final class HydrologyMosaicJoinV2 {
    private HydrologyMosaicJoinV2() {}

    public static List<HydrologyFlowGraphV2.Node> merge(
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> mosaics) {
        Objects.requireNonNull(mosaics);
        var merged=new TreeMap<GlobalHydrologyAddressV2.Sample,
                               HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var mosaic:mosaics) {
            Objects.requireNonNull(mosaic);
            for(var node:mosaic) {
                Objects.requireNonNull(node);
                var old=merged.get(node.address());
                if(old==null) {
                    merged.put(node.address(),node);
                    continue;
                }
                double water;
                if(Double.compare(old.localWater(),node.localWater())==0)
                    water=old.localWater();
                else if(old.localWater()==0)water=node.localWater();
                else if(node.localWater()==0)water=old.localWater();
                else throw new IllegalArgumentException(
                    "Conflicting rainfall at shared sample "+node.address());

                if(old.terminal() && node.downstream()!=null ||
                   node.terminal() && old.downstream()!=null)
                    throw new IllegalArgumentException(
                        "Conflicting outlet at shared sample "+node.address());

                var downstream=old.downstream()!=null?old.downstream():node.downstream();
                if(old.downstream()!=null&&node.downstream()!=null&&
                   !old.downstream().equals(node.downstream()))
                    throw new IllegalArgumentException(
                        "Conflicting routes at shared sample "+node.address());
                merged.put(node.address(),new HydrologyFlowGraphV2.Node(
                    node.address(),water,downstream,old.terminal()||node.terminal()));
            }
        }
        if(merged.isEmpty())throw new IllegalArgumentException("No mosaic samples");
        return List.copyOf(merged.values());
    }
}
