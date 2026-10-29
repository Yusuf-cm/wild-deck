package com.wilddeck.world;

import java.util.*;

/**
 * Generates deterministic hydrology graphs directly from seeded planet terrain.
 * Each region is generated as a one-region mosaic. Shared boundary vertices
 * have one rainfall owner to prevent overlap from creating extra water.
 *
 * This is a bounded loader, not a global river-routing oracle. Independently
 * filled mosaics may disagree on shared spill directions and will be rejected
 * by the mosaic join rather than silently reconciled.
 */
public final class HydrologyGeneratedRegionProviderV2
        implements HydrologyRegionSchedulerV2.Provider {
    private final PlanetSurfaceV2 terrain;
    private final PlanetClimateV2 climate;
    private final int level;
    private final int divisions;
    private final double riverThreshold;

    public HydrologyGeneratedRegionProviderV2(PlanetSurfaceV2 terrain,
            PlanetClimateV2 climate,int level,int divisions,double riverThreshold) {
        this.terrain=Objects.requireNonNull(terrain);
        this.climate=Objects.requireNonNull(climate);
        if(level<0||divisions<1||!Double.isFinite(riverThreshold)||riverThreshold<0)
            throw new IllegalArgumentException("Invalid generated region parameters");
        this.level=level;
        this.divisions=divisions;
        this.riverThreshold=riverThreshold;
    }

    @Override public Collection<HydrologyFlowGraphV2.Node> load(
            HydrologyRegionSchedulerV2.Region region) {
        Objects.requireNonNull(region);
        var generated=RegionalHydrologyV2.generate(
            terrain,climate,level,region.column(),region.row(),
            1,1,divisions,riverThreshold);
        var nodes=HydrologyGraphAdapterV2.nodes(
            generated,level,divisions,region.column(),region.row());
        var rain=generated.terrain().rainfall();
        var owned=HydrologyRainfallOwnershipV2.ownedRainfall(
            rain,generated.terrain().width(),generated.terrain().height(),
            divisions,region.column(),region.row());
        var result=new ArrayList<HydrologyFlowGraphV2.Node>(nodes.size());
        for(int i=0;i<nodes.size();i++){
            var node=nodes.get(i);
            result.add(new HydrologyFlowGraphV2.Node(
                node.address(),owned[i],node.downstream(),node.terminal()));
        }
        return List.copyOf(result);
    }
}
