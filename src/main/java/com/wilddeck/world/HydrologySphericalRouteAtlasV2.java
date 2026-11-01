package com.wilddeck.world;

import java.util.*;

/**
 * Immutable, bounded authoritative route atlas built from a CLOSED spherical
 * priority-flood solve. A region can be unloaded while its canonical route,
 * verified outlet catchment and stable reach IDs remain queryable.
 *
 * This is an opt-in precomputed snapshot, NOT incremental depression filling:
 * building it still requires the entire single-resolution terrain and verified
 * outlets. Terrain edits, ocean-mask changes and changed outlet topology require
 * rebuilding the atlas before using its identities or slices.
 *
 * Slices contain only uniquely owned LOCAL source water. Their standalone
 * discharge is not a global accumulated discharge: upstream receipts must be
 * delivered by the durable frontier ledger, not re-added from this atlas.
 */
public final class HydrologySphericalRouteAtlasV2 {
    private final List<HydrologyFlowGraphV2.Node> routes;
    private final Map<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node> byAddress;
    private final Map<GlobalHydrologyAddressV2.Sample,HydrologyRiverIdentityV2.CatchmentId> catchments;
    private final Map<HydrologyRiverIdentityV2.ReachId,HydrologyRiverIdentityV2.CatchmentId> reaches;
    private final int level,divisions;

    private HydrologySphericalRouteAtlasV2(List<HydrologyFlowGraphV2.Node> verified) {
        routes=List.copyOf(verified);
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:routes)
            if(nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException("Duplicate atlas source");
        byAddress=Collections.unmodifiableMap(nodes);
        var solved=HydrologyFlowGraphV2.solve(routes);
        HydrologyConservationV2.verify(routes,solved);
        if(!solved.pendingOutflow().isEmpty() ||
           solved.resolvedCatchments().size()!=routes.size())
            throw new IllegalArgumentException("Unresolved global atlas catchment");
        catchments=HydrologyRiverIdentityV2.catchments(solved);
        reaches=HydrologyRiverIdentityV2.resolvedReaches(routes,solved);
        level=routes.get(0).address().level();
        divisions=routes.get(0).address().divisions();
    }

    public static HydrologySphericalRouteAtlasV2 compile(
            Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
        return new HydrologySphericalRouteAtlasV2(
            HydrologySphericalBasinRouterV2.route(sources,elevations,verifiedOutlets));
    }

    public static HydrologySphericalRouteAtlasV2 compileWithOcean(
            Collection<HydrologyGlobalSlopeRouterV2.Source> sources,
            HydrologyGlobalSlopeRouterV2.Elevations elevations,
            double seaLevel,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOceanSeeds) {
        return new HydrologySphericalRouteAtlasV2(
            HydrologySphericalOceanOutletsV2.route(
                sources,elevations,seaLevel,verifiedOceanSeeds));
    }

    public List<HydrologyFlowGraphV2.Node> routes() { return routes; }
    public Map<GlobalHydrologyAddressV2.Sample,HydrologyRiverIdentityV2.CatchmentId>
            catchments() { return catchments; }
    public Map<HydrologyRiverIdentityV2.ReachId,HydrologyRiverIdentityV2.CatchmentId>
            reaches() { return reaches; }

    /** Return the authoritative edge for a sample even if its tile is unloaded. */
    public HydrologyFlowGraphV2.Node routeAt(GlobalHydrologyAddressV2.Sample address) {
        var node=byAddress.get(Objects.requireNonNull(address));
        if(node==null)throw new IllegalArgumentException("Unknown atlas sample");
        return node;
    }

    /**
     * Reuse verified global topology for a new seasonal water epoch. Exactly
     * one volume per canonical sample is required, so rainfall is never
     * duplicated or silently omitted at shared tile vertices. This does not
     * refresh routing after erosion or a changed ocean/shoreline mask.
     */
    public List<HydrologyFlowGraphV2.Node> withLocalWater(
            Collection<HydrologyGlobalSlopeRouterV2.Source> seasonalSources) {
        Objects.requireNonNull(seasonalSources);
        var water=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var source:seasonalSources) {
            Objects.requireNonNull(source);
            if(!byAddress.containsKey(source.address()) ||
               water.putIfAbsent(source.address(),source.localWater())!=null)
                throw new IllegalArgumentException("Unknown or duplicate seasonal source");
        }
        if(!water.keySet().equals(byAddress.keySet()))
            throw new IllegalArgumentException("Missing seasonal source owner");
        var updated=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:routes)
            updated.add(new HydrologyFlowGraphV2.Node(node.address(),
                water.get(node.address()),node.downstream(),node.terminal()));
        var result=List.copyOf(updated);
        HydrologyConservationV2.verify(result,HydrologyFlowGraphV2.solve(result));
        return result;
    }

    /** Deterministic, disjoint ownership matching spherical region providers. */
    public List<HydrologyFlowGraphV2.Node> ownedRegion(
            HydrologyRegionSchedulerV2.Region region) {
        Objects.requireNonNull(region);
        long columns=2L<<level,rows=1L<<level;
        if(region.column()<0||region.column()>=columns ||
           region.row()<0||region.row()>=rows)
            throw new IllegalArgumentException("Region outside atlas");
        var owned=new ArrayList<HydrologyFlowGraphV2.Node>();
        long maxY=rows*divisions;
        for(var node:routes) {
            var address=node.address();
            long column=address.x()==0?0:(address.x()-1)/divisions;
            long row=address.y()==0?0:(address.y()-1)/divisions;
            if(address.y()==maxY)column=0;
            if(column==region.column()&&row==region.row())owned.add(node);
        }
        return List.copyOf(owned);
    }

    /**
     * Check that independently loaded owned regions cover the exact snapshot
     * without duplicate rainfall vertices, missing vertices or changed edges.
     * This validates ownership, not receipt delivery or a new terrain epoch.
     */
    public void verifyPartition(
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> regions) {
        Objects.requireNonNull(regions);
        var combined=new TreeMap<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var region:regions) {
            Objects.requireNonNull(region);
            for(var node:region) {
                Objects.requireNonNull(node);
                if(combined.putIfAbsent(node.address(),node)!=null)
                    throw new IllegalArgumentException("Duplicate owned rainfall vertex");
            }
        }
        if(!combined.equals(byAddress))
            throw new IllegalArgumentException("Incomplete or conflicting atlas partition");
    }
}
