package com.wilddeck.world;

import java.io.IOException;
import java.util.*;

/**
 * Opt-in bridge from a caller-verified CLOSED spherical lake-basin plan to
 * durable, unloaded-region lake spill receipts. The basin plan supplies the
 * spill edges: callers do not get to invent a different downstream neighbor.
 *
 * This is deliberately a lake-only forcing epoch. River runoff/receipts are
 * NOT intercepted by this adapter. Every plan route must have zero localWater,
 * and pending receipts into any lake cell are rejected instead of silently
 * bypassing lake storage. Use the closed lake network step when river receipts
 * must enter a lake. A lake draining directly into another lake also fails
 * closed until a coupled multi-lake frontier solver exists.
 *
 * Requires a complete single-resolution spherical plan with caller-verified
 * outlets; it cannot discover oceans or solve arbitrary unloaded terrain.
 */
public final class HydrologyVerifiedLakeFrontierV2 {
    private HydrologyVerifiedLakeFrontierV2() {}

    public record Forcing(double contributingArea,
                          SeasonalLakeCycleV2.Period period) {
        public Forcing {
            Objects.requireNonNull(period);
            if(!Double.isFinite(contributingArea)||contributingArea<0)
                throw new IllegalArgumentException("Invalid lake catchment area");
        }
    }

    public static HydrologyLakeEpochV2.Advanced advance(
            HydrologyWorldRunnerV2 runner,String nextWorld,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Forcing> forcing,
            int substeps) throws IOException {
        Objects.requireNonNull(runner);
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(forcing);

        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:plan.routes()) {
            Objects.requireNonNull(node);
            if(!HydrologySphericalTopologyV2.isCanonical(node.address()) ||
               nodes.putIfAbsent(node.address(),node)!=null ||
               node.localWater()!=0.0)
                throw new IllegalArgumentException(
                    "Lake-only frontier needs unique zero-runoff spherical vertices");
        }
        if(nodes.isEmpty())throw new IllegalArgumentException("Empty lake drainage plan");
        for(var address:nodes.keySet())
            if(!nodes.keySet().containsAll(HydrologySphericalTopologyV2.neighbors(address)))
                throw new IllegalArgumentException("Incomplete spherical drainage plan");
        var solved=HydrologyFlowGraphV2.solve(nodes.values());
        HydrologyConservationV2.verify(nodes.values(),solved);
        if(!solved.pendingOutflow().isEmpty() ||
           solved.resolvedCatchments().size()!=nodes.size())
            throw new IllegalArgumentException("Unverified global lake drainage");

        var lakes=new TreeMap<GlobalHydrologyAddressV2.Sample,
                              HydrologySphericalLakeBasinsV2.Lake>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var lakeCells=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var lake:plan.lakes()) {
            Objects.requireNonNull(lake);
            if(lakes.putIfAbsent(lake.anchor(),lake)!=null ||
               !HydrologySphericalTopologyV2.isCanonical(lake.anchor()) ||
               lake.cells().isEmpty() ||
               !lake.anchor().equals(Collections.min(
                   lake.cells(),HydrologyFlowGraphV2.ADDRESS_ORDER)) ||
               lake.reservoir().bedElevation().length!=lake.cells().size())
                throw new IllegalArgumentException("Invalid lake geometry or identity");
            var unique=new HashSet<GlobalHydrologyAddressV2.Sample>(lake.cells());
            if(unique.size()!=lake.cells().size() || !nodes.keySet().containsAll(unique))
                throw new IllegalArgumentException("Duplicate or unknown lake cell");
            for(var cell:unique)
                if(!lakeCells.add(cell))
                    throw new IllegalArgumentException("Overlapping lake cells");
            var visited=new HashSet<GlobalHydrologyAddressV2.Sample>();
            var queue=new ArrayDeque<GlobalHydrologyAddressV2.Sample>();
            queue.add(lake.anchor());
            visited.add(lake.anchor());
            while(!queue.isEmpty())
                for(var neighbor:HydrologySphericalTopologyV2.neighbors(queue.remove()))
                    if(unique.contains(neighbor)&&visited.add(neighbor))
                        queue.add(neighbor);
            if(visited.size()!=unique.size())
                throw new IllegalArgumentException("Disconnected lake geometry");
        }
        if(lakes.isEmpty() || !lakes.keySet().equals(forcing.keySet()))
            throw new IllegalArgumentException("Missing or extra lake forcing");

        // Never absorb an incoming river receipt into seasonal precipitation.
        // The frontier adapter cannot intercept that receipt before spill.
        var inbox=runner.inboxSnapshot();
        long nextEpoch=Math.addExact(runner.epoch(),1);
        for(var key:inbox.receipts().keySet())
            if(key.epoch()<=nextEpoch && !inbox.delivered().contains(key) &&
               lakeCells.contains(key.to()))
                throw new IllegalArgumentException(
                    "Pending river receipt requires lake-aware routing");

        var edges=new ArrayList<HydrologyFlowGraphV2.Node>();
        var periods=new ArrayList<HydrologyLakeEpochV2.FrontierPeriod>();
        var spillSources=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var lake:lakes.values()) {
            var unique=new HashSet<>(lake.cells());
            if(unique.contains(lake.spillTarget()) ||
               lakeCells.contains(lake.spillTarget()) ||
               !lake.confirmedOutlet().equals(
                   solved.resolvedCatchments().get(lake.spillTarget())))
                throw new IllegalArgumentException("Unverified or coupled lake outlet");
            GlobalHydrologyAddressV2.Sample spillFrom=null;
            for(var cell:lake.cells()) {
                var downstream=nodes.get(cell).downstream();
                if(downstream!=null&&!unique.contains(downstream)) {
                    if(!downstream.equals(lake.spillTarget()) || spillFrom!=null)
                        throw new IllegalArgumentException("Ambiguous lake spill source");
                    spillFrom=cell;
                }
            }
            if(spillFrom==null || !spillSources.add(spillFrom) ||
               !HydrologySphericalTopologyV2.adjacent(spillFrom,lake.spillTarget()))
                throw new IllegalArgumentException("No verified lake spill edge");
            var input=Objects.requireNonNull(forcing.get(lake.anchor()));
            edges.add(new HydrologyFlowGraphV2.Node(
                spillFrom,0,lake.spillTarget(),false));
            periods.add(new HydrologyLakeEpochV2.FrontierPeriod(
                lake.anchor(),spillFrom,lake.spillTarget(),lake.reservoir(),
                input.contributingArea(),input.period()));
        }
        return HydrologyLakeEpochV2.advanceWithFrontier(
            runner,nextWorld,edges,periods,substeps);
    }
}
