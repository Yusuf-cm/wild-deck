package com.wilddeck.world;

import java.util.*;

/**
 * Globally consistent D8 provider for bounded interior planet regions.
 *
 * Only the east/south-owned vertices of each tile are emitted. Thus
 * independently loaded adjacent tiles neither duplicate rainfall nor
 * disagree about the next strictly downhill sample. Neighbor elevations
 * are sampled from the same seed even when the neighbor tile is unloaded.
 *
 * Pits and flats are unresolved, not invented outlets. Longitude seam,
 * poles, depressions and physically calibrated precipitation remain outside
 * this contract. Use the frontier-aware runner to reject unresolved routes
 * rather than discard water.
 */
public final class HydrologySeededSlopeRegionProviderV2
        implements HydrologyRegionSchedulerV2.Provider {
    private final PlanetClimateV2 climate;
    private final HydrologyGlobalSlopeRouterV2.Elevations elevations;
    private final int level,divisions;
    private final double volumePerMoisture;
    private final Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets;

    public HydrologySeededSlopeRegionProviderV2(
            PlanetSurfaceV2 terrain,PlanetClimateV2 climate,
            int level,int divisions,double volumePerMoisture,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
        this.climate=Objects.requireNonNull(climate);
        this.elevations=HydrologyGlobalSlopeRouterV2.planetElevations(
            Objects.requireNonNull(terrain));
        Objects.requireNonNull(verifiedOutlets);
        if(level<0||level>30||divisions<1||divisions>128||
           !Double.isFinite(volumePerMoisture)||volumePerMoisture<0)
            throw new IllegalArgumentException("Invalid region parameters");
        for(var outlet:verifiedOutlets) {
            Objects.requireNonNull(outlet);
            if(outlet.level()!=level||outlet.divisions()!=divisions)
                throw new IllegalArgumentException("Mixed outlet resolution");
        }
        this.level=level;
        this.divisions=divisions;
        this.volumePerMoisture=volumePerMoisture;
        this.verifiedOutlets=Set.copyOf(verifiedOutlets);
    }

    @Override public Collection<HydrologyFlowGraphV2.Node> load(
            HydrologyRegionSchedulerV2.Region region) {
        Objects.requireNonNull(region);
        var tile=new PlanetRegionGrid.Region(level,region.column(),region.row());
        var sources=HydrologyGlobalSlopeRouterV2.seededOwnedInteriorRegion(
            climate,tile,divisions,volumePerMoisture);
        var owned=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var source:sources)owned.add(source.address());
        var localOutlets=new HashSet<GlobalHydrologyAddressV2.Sample>(verifiedOutlets);
        localOutlets.retainAll(owned);
        return HydrologyGlobalSlopeRouterV2.route(sources,elevations,localOutlets);
    }
}
