package com.wilddeck.world;

import java.util.*;

/**
 * Bounded, deterministic downhill tracing through globally sampled but
 * unloaded spherical tiles. A trace may cross region boundaries and the
 * longitude seam without loading a region or duplicating a shared vertex.
 *
 * This is deliberately NOT priority flood: a pit/flat is unresolved, and
 * budget exhaustion is not a verified catchment or river mouth. No water is
 * injected, receipts are consumed, or checkpoints are modified here.
 */
public final class HydrologyUnloadedSphericalTraceV2 {
    private HydrologyUnloadedSphericalTraceV2() {}
    public static final int MAX_VISITS=100_000;

    public enum Status { VERIFIED_OUTLET, UNRESOLVED_PIT_OR_FLAT, BUDGET_EXHAUSTED }

    /**
     * visited contains canonical, uniquely owned vertices in route order.
     * next is non-null only when the visit budget was exhausted, allowing
     * the caller to resume the same deterministic trace in a later batch.
     */
    public record Trace(List<GlobalHydrologyAddressV2.Sample> visited,
                        GlobalHydrologyAddressV2.Sample next,Status status) {
        public Trace {
            visited=List.copyOf(visited);
            Objects.requireNonNull(status);
            if(visited.isEmpty() ||
               (status==Status.BUDGET_EXHAUSTED)!=(next!=null))
                throw new IllegalArgumentException("Invalid trace termination");
        }
    }

    public static Trace follow(
            GlobalHydrologyAddressV2.Sample start,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets,
            int maximumVisits) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(elevations);
        Objects.requireNonNull(verifiedOutlets);
        if(!HydrologySphericalTopologyV2.isCanonical(start) ||
           maximumVisits<1||maximumVisits>MAX_VISITS)
            throw new IllegalArgumentException("Invalid spherical trace start or budget");
        for(var outlet:verifiedOutlets) {
            if(outlet==null || !HydrologySphericalTopologyV2.isCanonical(outlet) ||
               outlet.level()!=start.level() ||
               outlet.divisions()!=start.divisions())
                throw new IllegalArgumentException("Invalid verified outlet resolution");
        }
        var visited=new ArrayList<GlobalHydrologyAddressV2.Sample>();
        var seen=new HashSet<GlobalHydrologyAddressV2.Sample>();
        var current=start;
        for(int i=0;i<maximumVisits;i++) {
            if(!seen.add(current))
                throw new IllegalArgumentException("Cyclic downhill trace");
            visited.add(current);
            if(verifiedOutlets.contains(current))
                return new Trace(visited,null,Status.VERIFIED_OUTLET);
            var node=HydrologySphericalTopologyV2.route(
                List.of(new HydrologyGlobalSlopeRouterV2.Source(current,0)),
                elevations,Set.of()).get(0);
            var next=node.downstream();
            if(next==null)
                return new Trace(visited,null,Status.UNRESOLVED_PIT_OR_FLAT);
            if(i==maximumVisits-1)
                return new Trace(visited,next,Status.BUDGET_EXHAUSTED);
            current=next;
        }
        throw new IllegalStateException("Unreachable trace state");
    }
}
