package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Opt-in, single-epoch lake-aware routing on an AUTHORITATIVE, closed,
 * single-resolution spherical basin plan. Every original flow source is
 * counted once: rivers stop at lake cells, the reservoir stores their
 * accumulated discharge, and only actual spill is released downstream.
 *
 * Additional inflow must be EXTERNAL to plan.routes().localWater (for example,
 * separately accounted snowmelt); supplying the same rainfall twice is a
 * caller error. Evaporation is an explicit start-of-substep wetted-area
 * approximation, not a calibrated lake heat/groundwater model. This class
 * does not discover ocean outlets or persist checkpoints automatically.
 */
public final class HydrologyLakeNetworkStepV2 {
    private HydrologyLakeNetworkStepV2() {}

    public record Forcing(double additionalInflow, double evaporationDepth,
                          int substeps) {
        public Forcing {
            if (!Double.isFinite(additionalInflow) || additionalInflow < 0 ||
                !Double.isFinite(evaporationDepth) || evaporationDepth < 0 ||
                substeps < 1 || substeps > 10_000)
                throw new IllegalArgumentException("Invalid lake forcing");
        }
    }

    public record Budget(double previousStorage, double sourceWater,
                         double additionalInflow, double evaporation,
                         double terminalOutflow, double finalStorage,
                         double residual) {}

    public record Result(HydrologyLakeStateV2.State state,
                         Map<GlobalHydrologyAddressV2.Sample,Double> terminalOutflow,
                         Map<GlobalHydrologyAddressV2.Sample,Double> lakeInflow,
                         Map<GlobalHydrologyAddressV2.Sample,Double> lakeOverflow,
                         Map<GlobalHydrologyAddressV2.Sample,Double> evaporation,
                         Budget budget) {
        public Result {
            Objects.requireNonNull(state);
            terminalOutflow=sorted(terminalOutflow);
            lakeInflow=sorted(lakeInflow);
            lakeOverflow=sorted(lakeOverflow);
            evaporation=sorted(evaporation);
            Objects.requireNonNull(budget);
        }
    }

    private record Destination(GlobalHydrologyAddressV2.Sample lake,
                               GlobalHydrologyAddressV2.Sample outlet) {}

    public static Result step(HydrologySphericalLakeBasinsV2.Plan plan,
                              HydrologyLakeStateV2.State previous,
                              Map<GlobalHydrologyAddressV2.Sample,Forcing> forcing) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(forcing);
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,
                              HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:plan.routes()) {
            Objects.requireNonNull(node);
            if(!HydrologySphericalTopologyV2.isCanonical(node.address()) ||
               nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException("Duplicate or noncanonical route sample");
        }
        // A partial streamed window must never be mistaken for a globally
        // authoritative lake plan merely because its local paths terminate.
        // Check closure before processing any storage or discharge.
        for(var address:nodes.keySet()) {
            if(!nodes.keySet().containsAll(
                    HydrologySphericalTopologyV2.neighbors(address)))
                throw new IllegalArgumentException(
                    "Lake routing requires a closed spherical sample graph");
        }
        var verified=HydrologyFlowGraphV2.solve(plan.routes());
        HydrologyConservationV2.verify(plan.routes(),verified);
        if(!verified.pendingOutflow().isEmpty() ||
           verified.resolvedCatchments().size()!=nodes.size())
            throw new IllegalArgumentException("Unverified planetary drainage");

        var lakes=new TreeMap<GlobalHydrologyAddressV2.Sample,
                              HydrologySphericalLakeBasinsV2.Lake>(
                                  HydrologyFlowGraphV2.ADDRESS_ORDER);
        var membership=new HashMap<GlobalHydrologyAddressV2.Sample,
                                   GlobalHydrologyAddressV2.Sample>();
        for(var lake:plan.lakes()) {
            Objects.requireNonNull(lake);
            if(!HydrologySphericalTopologyV2.isCanonical(lake.anchor()) ||
               lakes.putIfAbsent(lake.anchor(),lake)!=null)
                throw new IllegalArgumentException("Duplicate lake identity");
            boolean hasExit=false;
            var unique=new HashSet<>(lake.cells());
            if(unique.size()!=lake.cells().size() ||
               unique.contains(lake.spillTarget()) ||
               !nodes.containsKey(lake.spillTarget()) ||
               !lake.confirmedOutlet().equals(
                   verified.resolvedCatchments().get(lake.spillTarget())))
                throw new IllegalArgumentException("Unverified lake spill");
            for(var cell:lake.cells()) {
                if(!nodes.containsKey(cell) ||
                   membership.putIfAbsent(cell,lake.anchor())!=null)
                    throw new IllegalArgumentException("Overlapping or missing lake cell");
                var downstream=nodes.get(cell).downstream();
                if(!unique.contains(downstream)) {
                    if(!lake.spillTarget().equals(downstream))
                        throw new IllegalArgumentException("Lake has unverified exit");
                    hasExit=true;
                }
            }
            if(!hasExit)
                throw new IllegalArgumentException("Lake has no routed spill exit");
        }
        if(!forcing.keySet().equals(lakes.keySet()) ||
           !lakes.keySet().containsAll(previous.volumes().keySet()))
            throw new IllegalArgumentException("Unmatched lake forcing or stored identity");
        for(var entry:forcing.entrySet())Objects.requireNonNull(entry.getValue());
        double previousStorage=0, sourceWater=0, external=0;
        for(var entry:previous.volumes().entrySet()) {
            if(entry.getValue()>lakes.get(entry.getKey()).reservoir().capacity())
                throw new IllegalArgumentException("Stored water exceeds lake capacity");
            previousStorage=add(previousStorage,entry.getValue());
        }
        for(var node:nodes.values())sourceWater=add(sourceWater,node.localWater());
        for(var f:forcing.values())external=add(external,f.additionalInflow());

        var cut=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:nodes.values())
            cut.add(membership.containsKey(node.address()) ?
                new HydrologyFlowGraphV2.Node(node.address(),node.localWater(),null,false) :
                node);
        var baseline=HydrologyFlowGraphV2.solve(cut);
        var incoming=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var anchor:lakes.keySet())incoming.put(anchor,0.0);
        for(var entry:baseline.pendingOutflow().entrySet()) {
            var owner=membership.get(entry.getKey());
            if(owner==null)
                throw new IllegalArgumentException("Unresolved non-lake river frontier");
            incoming.put(owner,add(incoming.get(owner),entry.getValue()));
        }

        var destinations=new HashMap<GlobalHydrologyAddressV2.Sample,Destination>();
        var indegree=new HashMap<GlobalHydrologyAddressV2.Sample,Integer>();
        for(var anchor:lakes.keySet())indegree.put(anchor,0);
        for(var lake:lakes.values()) {
            var cursor=lake.spillTarget();
            while(!membership.containsKey(cursor) && !nodes.get(cursor).terminal()) {
                cursor=nodes.get(cursor).downstream();
                if(cursor==null || !nodes.containsKey(cursor))
                    throw new IllegalArgumentException("Unresolved lake overflow path");
            }
            var nextLake=membership.get(cursor);
            if(lake.anchor().equals(nextLake))
                throw new IllegalArgumentException("Lake spills back into itself");
            destinations.put(lake.anchor(),
                nextLake!=null?new Destination(nextLake,null):
                               new Destination(null,cursor));
            if(nextLake!=null)indegree.merge(nextLake,1,Integer::sum);
        }
        var ready=new PriorityQueue<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var entry:indegree.entrySet())
            if(entry.getValue()==0)ready.add(entry.getKey());

        var terminal=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        terminal.putAll(baseline.terminalOutflow());
        var inflows=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var overflows=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var evaporated=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var stored=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        int processed=0;
        while(!ready.isEmpty()) {
            var anchor=ready.remove();
            processed++;
            var lake=lakes.get(anchor);
            var f=forcing.get(anchor);
            double input=add(incoming.get(anchor),f.additionalInflow());
            inflows.put(anchor,input);
            double volume=previous.volumes().getOrDefault(anchor,0.0);
            double evaporation=0,overflow=0;
            double delivered=0;
            for(int i=0;i<f.substeps();i++) {
                double portion=i==f.substeps()-1?input-delivered:input/f.substeps();
                delivered=add(delivered,portion);
                var next=LakeReservoirV2.step(lake.reservoir(),volume,
                    portion,f.evaporationDepth()/f.substeps());
                volume=next.volume();
                evaporation=add(evaporation,next.evaporated());
                overflow=add(overflow,next.overflow());
            }
            stored.put(anchor,volume);
            evaporated.put(anchor,evaporation);
            overflows.put(anchor,overflow);
            var destination=destinations.get(anchor);
            if(destination.lake()!=null) {
                var downstream=destination.lake();
                incoming.put(downstream,add(incoming.get(downstream),overflow));
                int remaining=indegree.merge(downstream,-1,Integer::sum);
                if(remaining==0)ready.add(downstream);
            } else {
                terminal.put(destination.outlet(),
                    add(terminal.getOrDefault(destination.outlet(),0.0),overflow));
            }
        }
        if(processed!=lakes.size())
            throw new IllegalArgumentException("Cyclic lake overflow dependencies");
        double finalStorage=sum(stored.values());
        double evaporation=sum(evaporated.values());
        double outlet=sum(terminal.values());
        BigDecimal left=decimal(previousStorage).add(decimal(sourceWater))
            .add(decimal(external));
        BigDecimal right=decimal(finalStorage).add(decimal(evaporation))
            .add(decimal(outlet));
        double residual=left.subtract(right).doubleValue();
        double tolerance=Math.max(1e-8,Math.max(left.doubleValue(),
            right.doubleValue())*1e-10);
        if(!Double.isFinite(residual)||Math.abs(residual)>tolerance)
            throw new IllegalArgumentException("Lake/river water conservation violated");
        return new Result(new HydrologyLakeStateV2.State(previous.world(),stored),
            terminal,inflows,overflows,evaporated,
            new Budget(previousStorage,sourceWater,external,evaporation,
                       outlet,finalStorage,residual));
    }

    private static BigDecimal decimal(double value) {
        if(!Double.isFinite(value))throw new IllegalArgumentException("Water budget overflow");
        return BigDecimal.valueOf(value);
    }
    private static double sum(Collection<Double> values) {
        double total=0;
        for(double value:values)total=add(total,value);
        return total;
    }
    private static double add(double a,double b) {
        double result=a+b;
        if(!Double.isFinite(result)||result<0)
            throw new IllegalArgumentException("Water volume overflow");
        return result;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> sorted(
            Map<GlobalHydrologyAddressV2.Sample,Double> values) {
        Objects.requireNonNull(values);
        var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        ordered.putAll(values);
        return Collections.unmodifiableMap(ordered);
    }
}
