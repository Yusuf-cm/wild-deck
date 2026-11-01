package com.wilddeck.world;

import java.util.*;

/**
 * Fail-closed terrain-change audit for a bounded authoritative spherical atlas.
 * Rebuilds from the same complete canonical graph and verified outlets, then
 * identifies changed edges, catchments and conservative upstream dependencies.
 *
 * This is NOT incremental priority flood. Affected samples are a conservative
 * invalidation set, not permission to route through unknown/unloaded terrain.
 * Any lake storage, queued frontier receipts or persistent water checkpoint
 * must be reconciled separately before activating the rebuilt atlas.
 */
public final class HydrologySphericalAtlasRebuildAuditV2 {
    private HydrologySphericalAtlasRebuildAuditV2() {}

    public record Result(
        HydrologySphericalRouteAtlasV2 rebuilt,
        Set<GlobalHydrologyAddressV2.Sample> changedRoutes,
        Set<GlobalHydrologyAddressV2.Sample> changedCatchments,
        Set<HydrologyRiverIdentityV2.ReachId> changedReaches,
        Set<GlobalHydrologyAddressV2.Sample> affectedSamples,
        String previousTopology) {
        public Result {
            Objects.requireNonNull(rebuilt);
            Objects.requireNonNull(previousTopology);
            changedRoutes=Set.copyOf(changedRoutes);
            changedCatchments=Set.copyOf(changedCatchments);
            changedReaches=Set.copyOf(changedReaches);
            affectedSamples=Set.copyOf(affectedSamples);
            if(!affectedSamples.containsAll(changedRoutes) ||
               !affectedSamples.containsAll(changedCatchments))
                throw new IllegalArgumentException("Incomplete drainage invalidation");
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

        // A reach may retain the same geometric edge but drain into a new
        // outlet. Its location-derived ID is stable, but its catchment
        // association must be invalidated for downstream consumers.
        var changedReaches=new HashSet<HydrologyRiverIdentityV2.ReachId>();
        var allReaches=new HashSet<>(previous.reaches().keySet());
        allReaches.addAll(next.reaches().keySet());
        for(var reach:allReaches)
            if(!Objects.equals(previous.reaches().get(reach),next.reaches().get(reach)))
                changedReaches.add(reach);

        // Even an unchanged immediate edge can have a changed outlet upstream.
        // Walk both old and new reversed drainage graphs, including across
        // canonical region boundaries, so invalidation is conservative.
        var affected=new HashSet<GlobalHydrologyAddressV2.Sample>(changedRoutes);
        affected.addAll(changedCatchments);
        var upstream=new HashMap<GlobalHydrologyAddressV2.Sample,
                                 Set<GlobalHydrologyAddressV2.Sample>>();
        indexUpstream(previous.routes(),upstream);
        indexUpstream(next.routes(),upstream);
        var queue=new ArrayDeque<GlobalHydrologyAddressV2.Sample>(affected);
        while(!queue.isEmpty()) {
            for(var source:upstream.getOrDefault(queue.remove(),Set.of()))
                if(affected.add(source))queue.add(source);
        }
        return new Result(next,changedRoutes,changedCatchments,changedReaches,affected,
            HydrologyDrainageSnapshotIdV2.of(previous));
    }

    /**
     * Fail-closed permission to reuse an unloaded region's pre-edit route
     * slice. Every supplied vertex must be uniquely owned, present in both
     * atlases, unchanged, and outside the conservative upstream invalidation.
     * Empty slices are rejected so missing regions cannot pass silently.
     */
    public static void verifyReusableSlice(
            HydrologySphericalRouteAtlasV2 previous,Result audit,
            Collection<HydrologyFlowGraphV2.Node> slice) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(audit);
        Objects.requireNonNull(slice);
        if(!audit.previousTopology().equals(HydrologyDrainageSnapshotIdV2.of(previous)))
            throw new IllegalArgumentException("Drainage audit belongs to another previous atlas");
        if(slice.isEmpty())
            throw new IllegalArgumentException("Empty region route slice");
        var seen=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:slice) {
            Objects.requireNonNull(node);
            var address=node.address();
            if(!seen.add(address) || audit.affectedSamples().contains(address) ||
               !node.equals(previous.routeAt(address)) ||
               !node.equals(audit.rebuilt().routeAt(address)) ||
               !Objects.equals(previous.catchments().get(address),
                               audit.rebuilt().catchments().get(address)))
                throw new IllegalArgumentException("Region route slice needs reconciliation");
        }
    }

    /**
     * Verify an entire region's uniquely owned snapshot, not just a subset.
     * A partial tile must never be accepted as a safe unloaded-region cache.
     */
    public static void verifyReusableRegion(
            HydrologySphericalRouteAtlasV2 previous,Result audit,
            HydrologyRegionSchedulerV2.Region region,
            Collection<HydrologyFlowGraphV2.Node> loaded) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(audit);
        Objects.requireNonNull(region);
        Objects.requireNonNull(loaded);
        var expected=previous.ownedRegion(region);
        if(expected.size()!=loaded.size() ||
           !new HashSet<>(expected).equals(new HashSet<>(loaded)))
            throw new IllegalArgumentException("Incomplete or conflicting region ownership");
        verifyReusableSlice(previous,audit,loaded);
    }

    private static void indexUpstream(
        List<HydrologyFlowGraphV2.Node> routes,
        Map<GlobalHydrologyAddressV2.Sample,
            Set<GlobalHydrologyAddressV2.Sample>> upstream) {
        for(var node:routes)
            if(node.downstream()!=null)
                upstream.computeIfAbsent(node.downstream(),ignored->new HashSet<>())
                    .add(node.address());
    }
}
