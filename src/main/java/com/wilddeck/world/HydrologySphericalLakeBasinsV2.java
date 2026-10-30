package com.wilddeck.world;

import java.util.*;

/**
 * Extracts deterministic, geographically anchored lake storage geometry from
 * a CLOSED single-resolution spherical priority-flood solve.
 *
 * Only submerged cells with the same potential spill stage are connected.
 * Multiple independent spill exits are rejected rather than pretending one
 * arbitrary outlet can carry a physically verified lake discharge. The
 * priority-flood graph describes POTENTIAL routing; a dry lake does not flow.
 *
 * The entire canonical sphere and caller-verified terminal outlets are still
 * required. This is not an ocean detector, partial-window lake solver, or
 * distributed lake-identity reconciliation service.
 */
public final class HydrologySphericalLakeBasinsV2 {
    private HydrologySphericalLakeBasinsV2() {}

    public record Lake(GlobalHydrologyAddressV2.Sample anchor,
                       List<GlobalHydrologyAddressV2.Sample> cells,
                       GlobalHydrologyAddressV2.Sample spillTarget,
                       GlobalHydrologyAddressV2.Sample confirmedOutlet,
                       LakeReservoirV2.Basin reservoir) {
        public Lake {
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(spillTarget);
            Objects.requireNonNull(confirmedOutlet);
            Objects.requireNonNull(reservoir);
            cells=List.copyOf(cells);
            if(cells.isEmpty()||!anchor.equals(cells.get(0)))
                throw new IllegalArgumentException("Invalid spherical lake anchor");
        }
    }

    public record Plan(List<HydrologyFlowGraphV2.Node> routes,List<Lake> lakes) {
        public Plan {
            routes=List.copyOf(routes);
            lakes=List.copyOf(lakes);
        }
    }

    public static Plan extract(Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets,
            double radiusMeters) {
        Objects.requireNonNull(elevations);
        var routes=HydrologySphericalBasinRouterV2.route(
            sources,elevations,verifiedOutlets);
        // The route solver independently checks cycles, conservation and
        // reachability. Catchments are assigned only to confirmed outlets.
        var solved=HydrologyFlowGraphV2.solve(routes);
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,
                              HydrologyFlowGraphV2.Node>(
                                  HydrologyFlowGraphV2.ADDRESS_ORDER);
        var heights=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:routes) {
            nodes.put(node.address(),node);
            double height=elevations.at(node.address());
            if(!Double.isFinite(height))
                throw new IllegalArgumentException("Nonfinite lake bed");
            heights.put(node.address(),height);
        }

        // Iterative path memoization avoids stack overflow on long rivers.
        var spill=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var start:nodes.keySet()) {
            if(spill.containsKey(start))continue;
            var path=new ArrayList<GlobalHydrologyAddressV2.Sample>();
            var visited=new HashSet<GlobalHydrologyAddressV2.Sample>();
            var cursor=start;
            while(!spill.containsKey(cursor)) {
                if(!visited.add(cursor))
                    throw new IllegalArgumentException("Cyclic spherical lake route");
                path.add(cursor);
                var node=nodes.get(cursor);
                if(node==null)
                    throw new IllegalArgumentException("Incomplete spherical lake route");
                if(node.terminal()) {
                    spill.put(cursor,heights.get(cursor));
                    break;
                }
                cursor=Objects.requireNonNull(node.downstream(),
                    "Unresolved spherical lake route");
            }
            for(int i=path.size()-1;i>=0;i--) {
                var address=path.get(i);
                if(spill.containsKey(address))continue;
                var downstream=nodes.get(address).downstream();
                spill.put(address,Math.max(heights.get(address),spill.get(downstream)));
            }
        }

        var wet=new TreeSet<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var address:nodes.keySet())
            if(spill.get(address)>heights.get(address))wet.add(address);
        var seen=new HashSet<GlobalHydrologyAddressV2.Sample>();
        var lakes=new ArrayList<Lake>();
        for(var start:wet) {
            if(!seen.add(start))continue;
            double level=spill.get(start);
            var group=new TreeSet<GlobalHydrologyAddressV2.Sample>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            var queue=new ArrayDeque<GlobalHydrologyAddressV2.Sample>();
            queue.add(start);
            while(!queue.isEmpty()) {
                var address=queue.remove();
                group.add(address);
                for(var neighbor:HydrologySphericalTopologyV2.neighbors(address))
                    if(wet.contains(neighbor)&&
                       Double.compare(spill.get(neighbor),level)==0&&
                       seen.add(neighbor))queue.add(neighbor);
            }
            var exits=new TreeSet<GlobalHydrologyAddressV2.Sample>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            var outlets=new HashSet<GlobalHydrologyAddressV2.Sample>();
            var beds=new double[group.size()];
            var areas=new double[group.size()];
            int index=0;
            for(var address:group) {
                var next=nodes.get(address).downstream();
                if(next!=null&&!group.contains(next))exits.add(next);
                var outlet=solved.resolvedCatchments().get(address);
                if(outlet==null)
                    throw new IllegalArgumentException("Unverified lake catchment");
                outlets.add(outlet);
                beds[index]=heights.get(address);
                areas[index++]=HydrologySphericalCellAreaV2.areaAt(
                    address,radiusMeters);
            }
            if(exits.size()!=1||outlets.size()!=1)
                throw new IllegalArgumentException(
                    "Lake requires one verified spill exit and catchment");
            lakes.add(new Lake(group.first(),List.copyOf(group),
                exits.first(),outlets.iterator().next(),
                new LakeReservoirV2.Basin(beds,areas,level)));
        }
        return new Plan(routes,lakes);
    }
}
