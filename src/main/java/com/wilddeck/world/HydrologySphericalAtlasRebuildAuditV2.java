package com.wilddeck.world;

import java.util.*;

/**
 * Fail-closed terrain-change audit for a bounded authoritative spherical atlas.
 * Rebuilds from the same complete canonical graph and verified outlets, then
 * identifies changed edges and identities. This is NOT incremental priority
 * flood and does not permit reusing old drainage after terrain edits.
 */
public final class HydrologySphericalAtlasRebuildAuditV2 {
    private HydrologySphericalAtlasRebuildAuditV2() {}

    public record Result(
        HydrologySphericalRouteAtlasV2 rebuilt,
        Set<GlobalHydrologyAddressV2.Sample> changedRoutes,
        Set<GlobalHydrologyAddressV2.Sample> changedCatchments) {
        public Result {
            Objects.requireNonNull(rebuilt);
            changedRoutes=Set.copyOf(changedRoutes);
            changedCatchments=Set.copyOf(changedCatchments);
        }
    }

    public static Result rebuild(
        HydrologySphericalRouteAtlasV2 previous,
        Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
        HydrologyGlobalSlopeRouterV2.Elevations elevations,
        Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
        Objects.requireNonNull(previous);
        var next=HydrologySphericalRouteAtlasV2.compile(sources,elevations,verifiedOutlets);
        if(!previous.catchments().keySet().equals(next.catchments().keySet()))
            throw new IllegalArgumentException("Terrain snapshot changed canonical sample ownership");
        var changedRoutes=new HashSet<GlobalHydrologyAddressV2.Sample>();
        var changedCatchments=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var address:previous.catchments().keySet()) {
            var oldRoute=previous.routeAt(address);
            var newRoute=next.routeAt(address);
            if(!Objects.equals(oldRoute.downstream(),newRoute.downstream()) ||
               oldRoute.terminal()!=newRoute.terminal())
                changedRoutes.add(address);
            if(!Objects.equals(previous.catchments().get(address),
                                next.catchments().get(address)))
                changedCatchments.add(address);
        }
        return new Result(next,changedRoutes,changedCatchments);
    }
}
