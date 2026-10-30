package com.wilddeck.world;

import java.io.IOException;
import java.util.*;

/**
 * Opt-in, single-writer lake-geometry epoch after erosion or deposition.
 *
 * Remaps wet split/merge storage, then queues displaced water across NEW
 * verified spill edges in the SAME checkpoint as the new lake volumes.
 * This geometry-only epoch has no precipitation or local river runoff.
 * Pending receipts entering changing lake cells are rejected rather than
 * silently bypassing storage. Other receipts remain parked.
 *
 * A shared spill destination receives one deterministic aggregate receipt,
 * not multiple copies of the same released volume. This is not a transient
 * hydraulic breach solver or distributed persistence.
 */
public final class HydrologyLakeGeometryEpochV2 {
    private HydrologyLakeGeometryEpochV2() {}

    public record Advanced(HydrologyLakeGeometryTransitionV2.Result geometry,
                           HydrologyDurableEpochV2.Routed routed) {
        public Advanced {
            Objects.requireNonNull(geometry);
            Objects.requireNonNull(routed);
        }
    }

    private record Verified(
            Map<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node> nodes,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> spillSources,
            Set<GlobalHydrologyAddressV2.Sample> lakeCells) {}

    public static Advanced advance(
            HydrologyWorldRunnerV2 runner,String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan oldPlan,
            HydrologySphericalLakeBasinsV2.Plan newPlan) throws IOException {
        Objects.requireNonNull(runner);
        Objects.requireNonNull(nextWorld);
        var old=verify(oldPlan);
        var next=verify(newPlan);
        if(!old.nodes().keySet().equals(next.nodes().keySet()))
            throw new IllegalArgumentException("Geometry epochs require the same spherical grid");
        long nextEpoch=Math.addExact(runner.epoch(),1);
        var inbox=runner.inboxSnapshot();
        var sensitive=new HashSet<>(old.lakeCells());
        sensitive.addAll(next.lakeCells());
        for(var key:inbox.receipts().keySet())
            if(key.epoch()<=nextEpoch && !inbox.delivered().contains(key) &&
               sensitive.contains(key.to()))
                throw new IllegalArgumentException(
                    "Pending water into changing lake geometry requires lake-aware routing");

        var previous=HydrologyLakeStateV2.decode(runner.worldState());
        var remapped=HydrologyWetLakeReconciliationV2.reconcile(
            previous,oldPlan,newPlan);
        var candidates=new TreeMap<GlobalHydrologyAddressV2.Sample,
                                   GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var lake:newPlan.lakes()) {
            var source=next.spillSources().get(lake.anchor());
            // Multiple lakes may share a downstream receiving vertex. Pick
            // one deterministic representative for the aggregate volume.
            candidates.putIfAbsent(lake.spillTarget(),source);
        }
        var loaded=new ArrayList<HydrologyFlowGraphV2.Node>();
        var used=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var entry:remapped.overflow().entrySet()) {
            var target=entry.getKey();
            var source=candidates.get(target);
            if(source==null||next.lakeCells().contains(target)||
               !used.add(source)||
               !HydrologySphericalTopologyV2.adjacent(source,target))
                throw new IllegalArgumentException("Unverified geometry overflow frontier");
            loaded.add(new HydrologyFlowGraphV2.Node(
                source,entry.getValue(),target,false));
        }
        for(var node:loaded)
            if(used.contains(node.downstream()))
                throw new IllegalArgumentException("Geometry overflow bypasses another lake");
        if(loaded.isEmpty()) {
            // A zero-water terminal advances the monotonic epoch without
            // inventing a zero-volume boundary transfer.
            for(var node:next.nodes().values()) {
                if(node.terminal()&&!sensitive.contains(node.address())) {
                    loaded.add(new HydrologyFlowGraphV2.Node(
                        node.address(),0,null,true));
                    break;
                }
            }
            if(loaded.isEmpty())
                throw new IllegalArgumentException("No safe geometry epoch marker");
        }
        for(var key:inbox.receipts().keySet())
            if(key.epoch()<=nextEpoch && !inbox.delivered().contains(key))
                for(var node:loaded)
                    if(key.to().equals(node.address()))
                        throw new IllegalArgumentException(
                            "Geometry-only epoch would consume an unrelated river receipt");

        var updated=new HydrologyLakeStateV2.State(
            nextWorld,remapped.state().volumes());
        var routed=runner.advanceAndQueueKnownFrontier(
            HydrologyLakeStateV2.encode(updated),List.of(loaded));
        var geometry=new HydrologyLakeGeometryTransitionV2.Result(
            updated,remapped.overflow(),remapped.initialStorage(),
            remapped.finalStorage(),remapped.released());
        return new Advanced(geometry,routed);
    }

    private static Verified verify(HydrologySphericalLakeBasinsV2.Plan plan) {
        Objects.requireNonNull(plan);
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:plan.routes()) {
            Objects.requireNonNull(node);
            if(!HydrologySphericalTopologyV2.isCanonical(node.address())||
               node.localWater()!=0.0||
               nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException("Invalid zero-runoff spherical geometry route");
        }
        if(nodes.isEmpty())throw new IllegalArgumentException("Empty planetary route");
        for(var address:nodes.keySet())
            if(!nodes.keySet().containsAll(HydrologySphericalTopologyV2.neighbors(address)))
                throw new IllegalArgumentException("Incomplete planetary drainage graph");
        var solved=HydrologyFlowGraphV2.solve(nodes.values());
        HydrologyConservationV2.verify(nodes.values(),solved);
        if(!solved.pendingOutflow().isEmpty()||
           solved.resolvedCatchments().size()!=nodes.size())
            throw new IllegalArgumentException("Unverified planetary drainage");
        var spill=new TreeMap<GlobalHydrologyAddressV2.Sample,
                              GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var membership=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var lake:plan.lakes()) {
            Objects.requireNonNull(lake);
            var cells=new HashSet<>(lake.cells());
            if(cells.size()!=lake.cells().size()||
               !lake.anchor().equals(Collections.min(
                   lake.cells(),HydrologyFlowGraphV2.ADDRESS_ORDER))||
               cells.contains(lake.spillTarget())||
               !nodes.containsKey(lake.spillTarget())||
               !lake.confirmedOutlet().equals(
                   solved.resolvedCatchments().get(lake.spillTarget())))
                throw new IllegalArgumentException("Invalid lake drainage identity");
            var visited=new HashSet<GlobalHydrologyAddressV2.Sample>();
            var queue=new ArrayDeque<GlobalHydrologyAddressV2.Sample>();
            queue.add(lake.anchor());
            visited.add(lake.anchor());
            while(!queue.isEmpty())
                for(var neighbor:HydrologySphericalTopologyV2.neighbors(queue.remove()))
                    if(cells.contains(neighbor)&&visited.add(neighbor))
                        queue.add(neighbor);
            if(visited.size()!=cells.size())
                throw new IllegalArgumentException("Disconnected geometry lake");
            GlobalHydrologyAddressV2.Sample from=null;
            for(var cell:lake.cells()) {
                if(!nodes.containsKey(cell)||!membership.add(cell))
                    throw new IllegalArgumentException("Overlapping or unknown lake cell");
                var downstream=nodes.get(cell).downstream();
                if(!cells.contains(downstream)) {
                    if(from!=null||!lake.spillTarget().equals(downstream))
                        throw new IllegalArgumentException("Unverified lake spillway");
                    from=cell;
                }
            }
            if(from==null||spill.putIfAbsent(lake.anchor(),from)!=null)
                throw new IllegalArgumentException("Missing lake spill source");
        }
        return new Verified(Collections.unmodifiableMap(nodes),
            Collections.unmodifiableMap(spill),Set.copyOf(membership));
    }
}
