package com.wilddeck.world;

import java.util.*;

/**
 * Solves an entire rectangular region mosaic in one priority-flood graph,
 * then exports cross-region channel links. Unlike independent tile solves,
 * this permits water to traverse internal tile boundaries without being
 * treated as a terminal outlet.
 *
 * This is bounded by ConnectedDrainageV2's one-million-sample limit and
 * does not stream or route through unloaded mosaics.
 */
public final class ConnectedHydrologyV2 {
    private ConnectedHydrologyV2(){}

    public record Result(RegionalHydrologyV2.Hydrology hydrology,
                         List<HydrologyBoundaryV2.Crossing> internalTransfers) {
        public Result { internalTransfers=List.copyOf(internalTransfers); }
    }

    public static Result generate(PlanetSurfaceV2 world,PlanetClimateV2 climate,
                                  int level,long firstColumn,long firstRow,
                                  int columns,int rows,int divisions,double threshold){
        var hydrology=RegionalHydrologyV2.generate(world,climate,level,firstColumn,firstRow,
                                                   columns,rows,divisions,threshold);
        int width=hydrology.terrain().width();
        var transfers=new ArrayList<HydrologyBoundaryV2.Crossing>();
        for(var channel:hydrology.rivers().channels()){
            int sourceX=channel.index()%width,sourceY=channel.index()/width;
            int targetX=channel.downstream()%width,targetY=channel.downstream()/width;
            if(sourceX/divisions!=targetX/divisions||
               sourceY/divisions!=targetY/divisions){
                // A shared grid vertex belongs to the same geographic point
                // in both regions; the transfer is informational, not an
                // extra injection into this already-connected solve.
                transfers.add(new HydrologyBoundaryV2.Crossing(
                    GlobalHydrologyAddressV2.at(level,divisions,firstColumn,firstRow,
                        hydrology.terrain().width(),hydrology.terrain().height(),channel.index()),
                    GlobalHydrologyAddressV2.at(level,divisions,firstColumn,firstRow,
                        hydrology.terrain().width(),hydrology.terrain().height(),channel.downstream()),
                    channel.flow()));
            }
        }
        return new Result(hydrology,transfers);
    }
}
