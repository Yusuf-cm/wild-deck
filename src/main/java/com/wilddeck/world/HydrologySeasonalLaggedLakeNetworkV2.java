package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Opt-in, noncommitting seasonal routing through an explicitly supplied lake
 * network. Each lake's spill takes at least one time slice to reach the next
 * lake. Spill arriving after the season remains pending at its destination.
 * No runoff is regenerated at shared vertices and verified ocean mouths are
 * never inferred from a lake spill edge.
 *
 * A slice is a caller-selected time interval, not a calibrated travel time.
 * The caller must persist the returned pending water exactly once before
 * another season and authenticate lake geometry, edges and climate forcing.
 */
public final class HydrologySeasonalLaggedLakeNetworkV2 {
    private HydrologySeasonalLaggedLakeNetworkV2() {}
    private static final int MAX_LAKES = 4096;
    private static final long MAX_LAKE_SLICES = 1_000_000;

    public record Lake(LakeReservoirV2.Basin basin, double previousVolumeM3,
                       double evaporationDepthMeters,
                       GlobalHydrologyAddressV2.Sample spillTo, int travelSlices) {
        public Lake {
            Objects.requireNonNull(basin);
            Objects.requireNonNull(spillTo);
            if (!Double.isFinite(previousVolumeM3) || previousVolumeM3 < 0 ||
                previousVolumeM3 > basin.capacity() ||
                !Double.isFinite(evaporationDepthMeters) || evaporationDepthMeters < 0 ||
                travelSlices < 1 || travelSlices > HydrologySeasonalLakeIntegratorV2.MAX_SUBSTEPS)
                throw new IllegalArgumentException("Invalid lagged lake geometry or forcing");
        }
    }

    /** Uniform local runoff and potential evaporation in each time slice. */
    public static HydrologySeasonalLakeRoutingV2.Result advanceUniform(
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,Lake> lakes, int slices) {
        return advance(routed,lakes,slices,null);
    }

    /**
     * Explicit chronological local runoff and potential evaporation. Each
     * schedule must have the same number of slices, and its totals must match
     * the audited original frontier runoff and lake evaporation forcing.
     * Upstream lake spill is added separately, never duplicated in a schedule.
     */
    public static HydrologySeasonalLakeRoutingV2.Result advanceScheduled(
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,
                List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules) {
        Objects.requireNonNull(schedules);
        if (schedules.isEmpty() && !Objects.requireNonNull(lakes).isEmpty())
            throw new IllegalArgumentException("Missing lake forcing schedules");
        int slices = schedules.isEmpty() ? 1 :
            Objects.requireNonNull(schedules.values().iterator().next()).size();
        return advance(routed,lakes,slices,schedules);
    }

    private static HydrologySeasonalLakeRoutingV2.Result advance(
            HydrologySeasonalGlobalRoutingV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,Lake> lakes, int slices,
            Map<GlobalHydrologyAddressV2.Sample,
                List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules) {
        Objects.requireNonNull(lakes);
        if (slices < 1 || slices > HydrologySeasonalLakeIntegratorV2.MAX_SUBSTEPS ||
            lakes.size() > MAX_LAKES || (long) slices*lakes.size() > MAX_LAKE_SLICES)
            throw new IllegalArgumentException("Lake routing slice budget exceeded");
        // Reuse the independent, rate-aware routing audit before touching water.
        var base = HydrologySeasonalLakeRoutingV2.advance(routed,Map.of());
        if (lakes.isEmpty()) {
            if (schedules != null && !schedules.isEmpty())
                throw new IllegalArgumentException("Unexpected lake schedules");
            return base;
        }
        var ordered = new TreeMap<GlobalHydrologyAddressV2.Sample,Lake>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        ordered.putAll(lakes);
        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var address : base.pendingM3().keySet()) own(owners,address);
        var outlets = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var address : routed.drainage().terminalOutflow().keySet()) {
            own(owners,address);
            outlets.add(HydrologyGlobalCatchmentsV2.coordinate(address));
        }
        var lakeCoordinates = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var entry : ordered.entrySet()) {
            var address = Objects.requireNonNull(entry.getKey());
            var lake = Objects.requireNonNull(entry.getValue());
            var coordinate = HydrologyGlobalCatchmentsV2.coordinate(address);
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                !lakeCoordinates.add(coordinate) || outlets.contains(coordinate))
                throw new IllegalArgumentException("Ambiguous or verified-mouth lake inlet");
            own(owners,address);
            if (!HydrologySphericalTopologyV2.isCanonical(lake.spillTo()) ||
                !HydrologySphericalTopologyV2.adjacent(address,lake.spillTo()) ||
                outlets.contains(HydrologyGlobalCatchmentsV2.coordinate(lake.spillTo())))
                throw new IllegalArgumentException("Invalid or ocean-directed lake spill");
        }
        var incomingLakeEdges = new HashSet<GlobalHydrologyAddressV2.Sample>();
        var indegree = new HashMap<GlobalHydrologyAddressV2.Sample,Integer>();
        for (var address : ordered.keySet()) indegree.put(address,0);
        for (var entry : ordered.entrySet()) {
            var destination = entry.getValue().spillTo();
            own(owners,destination);
            if (ordered.containsKey(destination)) {
                incomingLakeEdges.add(destination);
                indegree.merge(destination,1,Integer::sum);
            }
        }
        for (var address : ordered.keySet())
            if (!base.pendingM3().containsKey(address) &&
                !incomingLakeEdges.contains(address))
                throw new IllegalArgumentException("Unowned or unreachable lake inlet");
        // Reject closed lake-to-lake cycles even though delayed simulation would
        // be numerically finite. A closed artificial spill loop is not a basin.
        var ready = new PriorityQueue<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : indegree.entrySet())
            if (entry.getValue()==0) ready.add(entry.getKey());
        int visited=0;
        while (!ready.isEmpty()) {
            var address=ready.remove();
            visited++;
            var destination=ordered.get(address).spillTo();
            if (ordered.containsKey(destination) &&
                indegree.merge(destination,-1,Integer::sum)==0)
                ready.add(destination);
        }
        if (visited!=ordered.size())
            throw new IllegalArgumentException("Cyclic lake spill network");

        if (schedules!=null) {
            if (!schedules.keySet().equals(ordered.keySet()))
                throw new IllegalArgumentException("Lake forcing schedule ownership mismatch");
            for (var entry : ordered.entrySet()) {
                var schedule=Objects.requireNonNull(schedules.get(entry.getKey()));
                if (schedule.size()!=slices)
                    throw new IllegalArgumentException("Inconsistent lake schedule length");
                BigDecimal local=BigDecimal.ZERO, evaporation=BigDecimal.ZERO;
                for (var interval : schedule) {
                    Objects.requireNonNull(interval);
                    local=local.add(number(interval.inflowM3()));
                    evaporation=evaporation.add(number(interval.evaporationDepthMeters()));
                }
                if (!close(local,number(base.pendingM3().getOrDefault(entry.getKey(),0.0))) ||
                    !close(evaporation,number(entry.getValue().evaporationDepthMeters())))
                    throw new IllegalArgumentException("Lake schedule duplicates or loses forcing");
            }
        }

        var pending = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        pending.putAll(base.pendingM3());
        for (var address : ordered.keySet()) pending.remove(address);
        var due = new HashMap<GlobalHydrologyAddressV2.Sample,double[]>();
        var volume = new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        var inflow = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var evaporated = new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        var spilled = new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        BigDecimal initial=BigDecimal.ZERO;
        for (var entry : ordered.entrySet()) {
            var address=entry.getKey();
            var lake=entry.getValue();
            due.put(address,new double[slices]);
            volume.put(address,lake.previousVolumeM3());
            inflow.put(address,0.0);
            evaporated.put(address,0.0);
            spilled.put(address,0.0);
            initial=initial.add(number(lake.previousVolumeM3()));
        }
        for (int i=0;i<slices;i++) {
            for (var entry : ordered.entrySet()) {
                var address=entry.getKey();
                var lake=entry.getValue();
                var interval=schedules==null ?
                    new HydrologySeasonalLakeIntegratorV2.Interval(
                        share(base.pendingM3().getOrDefault(address,0.0),i,slices),
                        share(lake.evaporationDepthMeters(),i,slices)) :
                    schedules.get(address).get(i);
                double received=add(interval.inflowM3(),due.get(address)[i]);
                var step=LakeReservoirV2.stepInflowThenEvaporation(lake.basin(),
                    volume.get(address),received,interval.evaporationDepthMeters());
                volume.put(address,step.volume());
                inflow.put(address,add(inflow.get(address),received));
                evaporated.put(address,add(evaporated.get(address),step.evaporated()));
                spilled.put(address,add(spilled.get(address),step.overflow()));
                if (step.overflow()>0) {
                    var destination=lake.spillTo();
                    int delivery=i+lake.travelSlices();
                    if (ordered.containsKey(destination) && delivery<slices) {
                        var bucket=due.get(destination);
                        bucket[delivery]=add(bucket[delivery],step.overflow());
                    } else {
                        pending.put(destination,add(
                            pending.getOrDefault(destination,0.0),step.overflow()));
                    }
                }
            }
        }
        var steps = new TreeMap<GlobalHydrologyAddressV2.Sample,LakeReservoirV2.Step>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        BigDecimal finalStorage=BigDecimal.ZERO, evaporation=BigDecimal.ZERO;
        for (var entry : ordered.entrySet()) {
            var address=entry.getKey();
            double stored=volume.get(address);
            double evap=evaporated.get(address);
            steps.put(address,new LakeReservoirV2.Step(stored,
                entry.getValue().basin().stageAt(stored),evap,spilled.get(address)));
            finalStorage=finalStorage.add(number(stored));
            evaporation=evaporation.add(number(evap));
        }
        BigDecimal pendingTotal=BigDecimal.ZERO;
        var rates = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : pending.entrySet()) {
            pendingTotal=pendingTotal.add(number(entry.getValue()));
            rates.put(entry.getKey(),entry.getValue()/routed.durationSeconds());
        }
        BigDecimal left=number(base.seasonalRunoffM3()).add(initial);
        BigDecimal right=number(base.verifiedOutletM3()).add(pendingTotal)
            .add(finalStorage).add(evaporation);
        if (!close(left,right))
            throw new IllegalArgumentException("Lagged lake network water conservation violated");
        return new HydrologySeasonalLakeRoutingV2.Result(steps,inflow,pending,rates,
            finite(initial),finite(finalStorage),finite(evaporation),
            base.verifiedOutletM3(),base.seasonalRunoffM3(),
            finite(left.subtract(right)));
    }

    private static void own(Map<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample> owners,
            GlobalHydrologyAddressV2.Sample address) {
        Objects.requireNonNull(address);
        if (!HydrologySphericalTopologyV2.isCanonical(address))
            throw new IllegalArgumentException("Noncanonical lake network vertex");
        var old=owners.putIfAbsent(HydrologyGlobalCatchmentsV2.coordinate(address),address);
        if (old!=null && !old.equals(address))
            throw new IllegalArgumentException("Duplicate physical lake network vertex");
    }

    private static double share(double total,int slice,int slices) {
        double fraction=total/slices;
        return slice==slices-1 ? Math.max(0,total-fraction*(slices-1)) : fraction;
    }

    private static double add(double a,double b) {
        double sum=a+b;
        if (!Double.isFinite(sum) || sum<0)
            throw new IllegalArgumentException("Lake transfer volume overflow");
        return sum;
    }

    private static BigDecimal number(double value) {
        if (!Double.isFinite(value) || value<0)
            throw new IllegalArgumentException("Invalid lake budget value");
        return BigDecimal.valueOf(value);
    }

    private static double finite(BigDecimal value) {
        double result=value.doubleValue();
        if (!Double.isFinite(result))
            throw new IllegalArgumentException("Lake budget overflow");
        return result;
    }

    private static boolean close(BigDecimal a,BigDecimal b) {
        double left=finite(a),right=finite(b);
        return Math.abs(left-right)<=Math.max(1e-8,
            1e-10*Math.max(Math.abs(left),Math.abs(right)));
    }
}
