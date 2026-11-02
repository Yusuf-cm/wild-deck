package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Opt-in, pure resolution of already-conserved frontier water through explicitly
 * known drainage edges. A loaded, independently verified global mouth can
 * receive old water as accounted outlet discharge; an unloaded or unknown
 * destination remains pending. This does NOT commit outlet receipts, discover
 * oceans, advance lakes, or provide exactly-once persistence.
 */
public final class HydrologySeasonalPendingOutletV2 {
    private HydrologySeasonalPendingOutletV2() {}

    public record Result(
            Map<GlobalHydrologyAddressV2.Sample,Double> pendingM3,
            Map<GlobalHydrologyAddressV2.Sample,Double> verifiedOutletM3,
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologyGlobalCatchmentsV2.CatchmentId> sourceCatchments,
            Set<HydrologyGlobalCatchmentsV2.ReachId> traversedReaches,
            double inputM3, double pendingTotalM3, double outletTotalM3) {
        public Result {
            pendingM3=ordered(pendingM3);
            verifiedOutletM3=ordered(verifiedOutletM3);
            sourceCatchments=ordered(sourceCatchments);
            var reaches=new TreeSet<HydrologyGlobalCatchmentsV2.ReachId>(
                Comparator.comparing(HydrologyGlobalCatchmentsV2.ReachId::stableKey));
            reaches.addAll(Objects.requireNonNull(traversedReaches));
            traversedReaches=Collections.unmodifiableSet(reaches);
            if (!Double.isFinite(inputM3) || inputM3<0 ||
                !Double.isFinite(pendingTotalM3) || pendingTotalM3<0 ||
                !Double.isFinite(outletTotalM3) || outletTotalM3<0)
                throw new IllegalArgumentException("Invalid outlet-routing totals");
        }
    }

    /**
     * The mouth set is a caller-authenticated contract: each mouth must be a
     * terminal known vertex with null downstream. Only LOADED mouths discharge.
     * Unloaded mouths, lakes, and unknown edges remain pending for another epoch.
     * Each canonical physical vertex has exactly one sampling-resolution owner.
     */
    public static Result route(
            Map<GlobalHydrologyAddressV2.Sample,Double> pending,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakeInlets,
            Set<GlobalHydrologyAddressV2.Sample> verifiedGlobalMouths) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(knownDownstream);
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(lakeInlets);
        Objects.requireNonNull(verifiedGlobalMouths);
        if (pending.size()>100_000 || knownDownstream.size()>1_000_000)
            throw new IllegalArgumentException("Frontier routing graph budget exceeded");
        var owners=new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var address:pending.keySet()) own(owners,address);
        for (var address:loaded) own(owners,address);
        for (var address:lakeInlets) own(owners,address);
        for (var address:verifiedGlobalMouths) own(owners,address);
        for (var entry:knownDownstream.entrySet()) {
            var from=Objects.requireNonNull(entry.getKey());
            own(owners,from);
            var to=entry.getValue();
            if (to!=null) {
                own(owners,to);
                if (!HydrologySphericalTopologyV2.adjacent(from,to))
                    throw new IllegalArgumentException("Nonadjacent frontier drainage edge");
            }
        }
        for (var mouth:verifiedGlobalMouths) {
            if (!knownDownstream.containsKey(mouth) ||
                knownDownstream.get(mouth)!=null || lakeInlets.contains(mouth))
                throw new IllegalArgumentException("Mouth is not a known terminal");
        }

        var inputs=ordered(pending);
        var unresolved=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var outlets=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var identities=new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyGlobalCatchmentsV2.CatchmentId>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
        var reaches=new TreeSet<HydrologyGlobalCatchmentsV2.ReachId>(
            Comparator.comparing(HydrologyGlobalCatchmentsV2.ReachId::stableKey));
        BigDecimal total=BigDecimal.ZERO;
        for (var entry:inputs.entrySet()) {
            var source=entry.getKey();
            double volume=Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(volume) || volume<0)
                throw new IllegalArgumentException("Invalid conserved frontier volume");
            total=total.add(BigDecimal.valueOf(volume));
            var at=source;
            var visited=new HashSet<GlobalHydrologyAddressV2.Sample>();
            while (loaded.contains(at) && !lakeInlets.contains(at) &&
                   !verifiedGlobalMouths.contains(at)) {
                if (!visited.add(at))
                    throw new IllegalArgumentException("Cyclic loaded drainage");
                var next=knownDownstream.get(at);
                if (next==null) break;
                reaches.add(new HydrologyGlobalCatchmentsV2.ReachId(
                    HydrologyGlobalCatchmentsV2.coordinate(at),
                    HydrologyGlobalCatchmentsV2.coordinate(next)));
                at=next;
            }
            if (loaded.contains(at) && verifiedGlobalMouths.contains(at)) {
                add(outlets,at,volume);
                identities.put(source,new HydrologyGlobalCatchmentsV2.CatchmentId(
                    HydrologyGlobalCatchmentsV2.coordinate(at)));
            } else {
                add(unresolved,at,volume);
            }
        }
        BigDecimal pendingTotal=sum(unresolved), outletTotal=sum(outlets);
        if (!Double.isFinite(total.doubleValue()) ||
            !Double.isFinite(pendingTotal.doubleValue()) ||
            !Double.isFinite(outletTotal.doubleValue()))
            throw new IllegalArgumentException("Aggregate frontier volume overflow");
        double residual=total.subtract(pendingTotal.add(outletTotal)).abs().doubleValue();
        if (!Double.isFinite(residual) ||
            residual>Math.max(1e-8,1e-10*total.doubleValue()))
            throw new IllegalArgumentException("Frontier outlet conservation failure");
        return new Result(unresolved,outlets,identities,reaches,
            total.doubleValue(),pendingTotal.doubleValue(),outletTotal.doubleValue());
    }

    private static void add(Map<GlobalHydrologyAddressV2.Sample,Double> map,
                            GlobalHydrologyAddressV2.Sample address,double value) {
        double combined=map.getOrDefault(address,0.0)+value;
        if (!Double.isFinite(combined))
            throw new IllegalArgumentException("Convergent frontier water overflow");
        map.put(address,combined==0?0.0:combined);
    }

    private static BigDecimal sum(Map<GlobalHydrologyAddressV2.Sample,Double> values) {
        BigDecimal result=BigDecimal.ZERO;
        for (double value:values.values())
            result=result.add(BigDecimal.valueOf(value));
        return result;
    }

    private static <T> Map<GlobalHydrologyAddressV2.Sample,T> ordered(
            Map<GlobalHydrologyAddressV2.Sample,T> source) {
        var result=new TreeMap<GlobalHydrologyAddressV2.Sample,T>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        result.putAll(Objects.requireNonNull(source));
        return Collections.unmodifiableMap(result);
    }

    private static void own(
            Map<HydrologyGlobalCatchmentsV2.Coordinate,
                GlobalHydrologyAddressV2.Sample> owners,
            GlobalHydrologyAddressV2.Sample address) {
        Objects.requireNonNull(address);
        if (!HydrologySphericalTopologyV2.isCanonical(address))
            throw new IllegalArgumentException("Noncanonical frontier address");
        var previous=owners.putIfAbsent(
            HydrologyGlobalCatchmentsV2.coordinate(address),address);
        if (previous!=null && !previous.equals(address))
            throw new IllegalArgumentException("Duplicate physical vertex across resolutions");
    }
}
