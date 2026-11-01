package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in seeded region provider with canonical dateline and polar ownership.
 * Every geographic sample has exactly one owner, even when neighboring
 * regions are unloaded. The east/south tile owns ordinary shared vertices;
 * the first longitude tile owns the dateline and the two polar vertices.
 *
 * Uses great-circle steepest descent; unresolved pits/flats stay unresolved.
 * The climate moisture-to-water conversion remains a caller-supplied proxy,
 * not a calibrated precipitation or cell-area model.
 */
public final class HydrologySphericalSlopeRegionProviderV2
        implements HydrologyRegionSchedulerV2.Provider {
    private final PlanetClimateV2 climate;
    private final HydrologyGlobalSlopeRouterV2.Elevations elevations;
    private final int level,divisions;
    private final double volumePerMoisture;
    private final Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets;

    public HydrologySphericalSlopeRegionProviderV2(
            PlanetSurfaceV2 terrain,PlanetClimateV2 climate,
            int level,int divisions,double volumePerMoisture,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets) {
        this(terrain,climate,level,divisions,volumePerMoisture,
             verifiedOutlets,null);
    }

    /** Use one checkpoint-restored canonical terrain snapshot per epoch. */
    public HydrologySphericalSlopeRegionProviderV2(
            PlanetSurfaceV2 terrain,PlanetClimateV2 climate,
            int level,int divisions,double volumePerMoisture,
            Set<GlobalHydrologyAddressV2.Sample> verifiedOutlets,
            HydrologyTerrainOverlayV2.State overlay) {
        this.climate=Objects.requireNonNull(climate);
        var seeded=HydrologyGlobalSlopeRouterV2.planetElevations(
            Objects.requireNonNull(terrain));
        Objects.requireNonNull(verifiedOutlets);
        if(level<0||level>30||divisions<1||divisions>128||
           !Double.isFinite(volumePerMoisture)||volumePerMoisture<0)
            throw new IllegalArgumentException("Invalid spherical region parameters");
        for(var outlet:verifiedOutlets) {
            Objects.requireNonNull(outlet);
            if(outlet.level()!=level||outlet.divisions()!=divisions||
               !HydrologySphericalTopologyV2.isCanonical(outlet))
                throw new IllegalArgumentException("Noncanonical or mixed outlet resolution");
        }
        if(overlay!=null)
            for(var address:overlay.elevations().keySet())
                if(!HydrologySphericalTopologyV2.isCanonical(address))
                    throw new IllegalArgumentException("Noncanonical terrain overlay address");
        this.elevations=overlay==null?seeded:
            HydrologyTerrainOverlayV2.elevations(seeded,overlay);
        this.level=level;
        this.divisions=divisions;
        this.volumePerMoisture=volumePerMoisture;
        this.verifiedOutlets=Set.copyOf(verifiedOutlets);
    }

    @Override public Collection<HydrologyFlowGraphV2.Node> load(
            HydrologyRegionSchedulerV2.Region region) {
        Objects.requireNonNull(region);
        var tile=new PlanetRegionGrid.Region(level,region.column(),region.row());
        long maxX=(2L<<level)*divisions,maxY=(1L<<level)*divisions;
        long rows=1L<<level;
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        int firstX=region.column()==0?0:1;
        for(int y=1;y<=divisions;y++){
            long globalY=region.row()*divisions+y;
            if(globalY==maxY)continue; // south pole is a single sample
            for(int x=firstX;x<=divisions;x++){
                long globalX=region.column()*divisions+x;
                if(globalX==maxX)continue; // duplicate dateline
                var address=new GlobalHydrologyAddressV2.Sample(
                    level,divisions,globalX,globalY);
                sources.add(new HydrologyGlobalSlopeRouterV2.Source(
                    address,water(tile,x,y)));
            }
        }
        if(region.column()==0&&region.row()==0)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(
                new GlobalHydrologyAddressV2.Sample(level,divisions,0,0),
                water(tile,0,0)));
        if(region.column()==0&&region.row()==rows-1)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(
                new GlobalHydrologyAddressV2.Sample(level,divisions,0,maxY),
                water(tile,0,divisions)));
        var owned=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var source:sources)owned.add(source.address());
        var localOutlets=new HashSet<GlobalHydrologyAddressV2.Sample>(verifiedOutlets);
        localOutlets.retainAll(owned);
        return HydrologySphericalTopologyV2.route(sources,elevations,localOutlets);
    }

    private double water(PlanetRegionGrid.Region tile,int x,int y) {
        double value=climate.sample(PlanetRegionGrid.point(tile,x,y,divisions))
            .moisture()*volumePerMoisture;
        if(!Double.isFinite(value))
            throw new IllegalArgumentException("Spherical rainfall overflow");
        return value;
    }
}
