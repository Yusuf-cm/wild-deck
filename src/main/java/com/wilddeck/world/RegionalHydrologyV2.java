package com.wilddeck.world;

import java.util.Objects;

/** Composes terrain, climate, connected drainage, lake spill and river channels. */
public final class RegionalHydrologyV2 {
    private RegionalHydrologyV2(){}
    public record Hydrology(ConnectedDrainageV2.Mosaic terrain,
                            LakeSpillV2.Result lakes,
                            SpillDischargeV2.Discharge spillDischarge,
                            RiverNetworkV2.Network rivers) {
        public Hydrology {
            Objects.requireNonNull(terrain);Objects.requireNonNull(lakes);Objects.requireNonNull(spillDischarge);Objects.requireNonNull(rivers);
        }
    }
    public static Hydrology generate(PlanetSurfaceV2 world,PlanetClimateV2 climate,
                                     int level,long column,long row,
                                     int columns,int rows,int divisions,double riverThreshold){
        var mosaic=ConnectedDrainageV2.generate(world,climate,level,column,row,columns,rows,divisions);
        var lakes=LakeSpillV2.fill(mosaic.width(),mosaic.height(),mosaic.elevation());
        var spillDischarge=SpillDischargeV2.accumulate(lakes,mosaic.rainfall());
        // River channels now follow the conserved spill discharge network.
        var rivers=RiverNetworkV2.extract(lakes,spillDischarge,riverThreshold);
        return new Hydrology(mosaic,lakes,spillDischarge,rivers);
    }
}
