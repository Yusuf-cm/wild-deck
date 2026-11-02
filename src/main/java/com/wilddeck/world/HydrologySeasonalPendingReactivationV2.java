package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Repositions already-conserved seasonal frontier water along authenticated,
 * loaded drainage edges. It does not generate precipitation, discover routes,
 * advance lake storage, or declare an unknown destination an ocean outlet.
 *
 * A known edge into an unloaded tile parks the water at that destination.
 * The caller must persist the resulting carryover with the same receipt
 * markers in one checkpoint before acknowledging any inbound transfer.
 */
public final class HydrologySeasonalPendingReactivationV2 {
    private HydrologySeasonalPendingReactivationV2() {}

    public static Map<GlobalHydrologyAddressV2.Sample,Double> reactivate(
            Map<GlobalHydrologyAddressV2.Sample,Double> pending,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> knownDownstream,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Set<GlobalHydrologyAddressV2.Sample> lakeInlets,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        Objects.requireNonNull(pending);
        Objects.requireNonNull(knownDownstream);
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(lakeInlets);
        Objects.requireNonNull(verifiedMouths);
        if (pending.size()>100_000 || knownDownstream.size()>1_000_000)
            throw new IllegalArgumentException("Reactivation graph budget exceeded");
        var physical=new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var address:pending.keySet()) own(physical,address);
        for (var address:loaded) own(physical,address);
        for (var address:lakeInlets) own(physical,address);
        for (var address:verifiedMouths) own(physical,address);
        for (var entry:knownDownstream.entrySet()) {
            var from=Objects.requireNonNull(entry.getKey());
            own(physical,from);
            var to=entry.getValue();
            if (to!=null) {
                own(physical,to);
                if (!HydrologySphericalTopologyV2.adjacent(from,to))
                    throw new IllegalArgumentException("Nonadjacent drainage reactivation edge");
            }
            if (verifiedMouths.contains(from) && to!=null)
                throw new IllegalArgumentException("Verified mouth has downstream");
        }
        var input=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        input.putAll(pending);
        var output=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        BigDecimal before=BigDecimal.ZERO;
        for (var entry:input.entrySet()) {
            double volume=Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(volume) || volume<0)
                throw new IllegalArgumentException("Invalid pending volume");
            before=before.add(BigDecimal.valueOf(volume));
            var at=entry.getKey();
            if (verifiedMouths.contains(at))
                throw new IllegalArgumentException("Unaccounted pending water at ocean mouth");
            var seen=new HashSet<GlobalHydrologyAddressV2.Sample>();
            while (loaded.contains(at) && !lakeInlets.contains(at)) {
                if (!seen.add(at))
                    throw new IllegalArgumentException("Cyclic loaded drainage route");
                var next=knownDownstream.get(at);
                if (next==null) break;
                if (verifiedMouths.contains(next))
                    throw new IllegalArgumentException(
                        "Cannot silently classify old frontier water as ocean discharge");
                at=next;
            }
            double total=output.getOrDefault(at,0.0)+volume;
            if (!Double.isFinite(total))
                throw new IllegalArgumentException("Reactivated water overflow");
            output.put(at,total==0?0.0:total);
        }
        BigDecimal after=BigDecimal.ZERO;
        for (double value:output.values()) after=after.add(BigDecimal.valueOf(value));
        if (!Double.isFinite(before.doubleValue()) ||
            !Double.isFinite(after.doubleValue()))
            throw new IllegalArgumentException("Reactivation total water overflow");
        double delta=before.subtract(after).abs().doubleValue();
        if (!Double.isFinite(delta) ||
            delta>Math.max(1e-8,1e-10*before.doubleValue()))
            throw new IllegalArgumentException("Reactivation water budget mismatch");
        return Collections.unmodifiableMap(output);
    }

    private static void own(
            Map<HydrologyGlobalCatchmentsV2.Coordinate,
                GlobalHydrologyAddressV2.Sample> physical,
            GlobalHydrologyAddressV2.Sample address) {
        Objects.requireNonNull(address);
        if (!HydrologySphericalTopologyV2.isCanonical(address))
            throw new IllegalArgumentException("Noncanonical reactivation address");
        var old=physical.putIfAbsent(
            HydrologyGlobalCatchmentsV2.coordinate(address),address);
        if (old!=null && !old.equals(address))
            throw new IllegalArgumentException("Cross-resolution physical vertex alias");
    }
}
