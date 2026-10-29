package com.wilddeck.world;

import java.util.*;

/**
 * Authoritative connected hydrology for one bounded rectangular terrain
 * window. Adjacent regions are sampled in one grid, so shared rainfall
 * vertices and spill routes are never independently counted or guessed.
 *
 * Different windows may disagree at their outer edges; this does not
 * establish a global watershed or automatically reconcile unloaded terrain.
 */
public final class HydrologyTerrainWindowV2 {
    private HydrologyTerrainWindowV2(){}

    public record Window(RegionalHydrologyV2.Hydrology hydrology,
                         List<HydrologyFlowGraphV2.Node> nodes) {
        public Window {
            Objects.requireNonNull(hydrology);
            nodes=List.copyOf(nodes);
        }
    }

    public static Window generate(PlanetSurfaceV2 terrain,PlanetClimateV2 climate,
                                  int level,long firstColumn,long firstRow,
                                  int columns,int rows,int divisions,double riverThreshold) {
        if(divisions<1||divisions>128)
            throw new IllegalArgumentException("Unsupported global address resolution");
        var hydrology=RegionalHydrologyV2.generate(
            terrain,climate,level,firstColumn,firstRow,columns,rows,divisions,riverThreshold);
        var nodes=HydrologyGraphAdapterV2.nodes(
            hydrology,level,divisions,firstColumn,firstRow);
        return new Window(hydrology,nodes);
    }
}
