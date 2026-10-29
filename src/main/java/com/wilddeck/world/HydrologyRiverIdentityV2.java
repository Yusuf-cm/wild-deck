package com.wilddeck.world;

import java.util.*;

/**
 * Location-derived identifiers for confirmed outlet catchments and river edges.
 * IDs survive changes in rainfall, tile loading and mosaic ordering as long as
 * the authoritative route and sampling resolution stay the same.
 *
 * An unresolved frontier has NO catchment identity. An outlet must be
 * physically verified by the caller; this is not a planet-wide basin oracle.
 */
public final class HydrologyRiverIdentityV2 {
    private HydrologyRiverIdentityV2(){}

    public record CatchmentId(GlobalHydrologyAddressV2.Sample outlet) {
        public CatchmentId { Objects.requireNonNull(outlet); }
    }

    public record ReachId(GlobalHydrologyAddressV2.Sample from,
                          GlobalHydrologyAddressV2.Sample to) {
        public ReachId {
            Objects.requireNonNull(to);
            // Reuse the validated one-step graph-edge contract.
            new HydrologyFlowGraphV2.Node(from,0,to,false);
        }
    }

    public static Map<GlobalHydrologyAddressV2.Sample,CatchmentId> catchments(
            HydrologyFlowGraphV2.Result graph) {
        Objects.requireNonNull(graph);
        var result=new TreeMap<GlobalHydrologyAddressV2.Sample,CatchmentId>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        graph.resolvedCatchments().forEach((sample,outlet)->
            result.put(sample,new CatchmentId(outlet)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Only fully resolved reaches are assigned a catchment. Their ReachId
     * itself remains stable even when the downstream endpoint is unloaded.
     */
    public static Map<ReachId,CatchmentId> resolvedReaches(
            Collection<HydrologyFlowGraphV2.Node> nodes,
            HydrologyFlowGraphV2.Result graph) {
        Objects.requireNonNull(nodes);
        Objects.requireNonNull(graph);
        var order=Comparator.comparing(ReachId::from,HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(ReachId::to,HydrologyFlowGraphV2.ADDRESS_ORDER);
        var result=new TreeMap<ReachId,CatchmentId>(order);
        for(var node:nodes){
            Objects.requireNonNull(node);
            if(node.downstream()==null)continue;
            var outlet=graph.resolvedCatchments().get(node.address());
            if(outlet==null)continue;
            if(!outlet.equals(graph.resolvedCatchments().get(node.downstream())))
                throw new IllegalArgumentException("Inconsistent catchment route");
            var id=new ReachId(node.address(),node.downstream());
            if(result.putIfAbsent(id,new CatchmentId(outlet))!=null)
                throw new IllegalArgumentException("Duplicate river reach");
        }
        return Collections.unmodifiableMap(result);
    }
}
