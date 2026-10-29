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

    /**
     * Solve a larger connected terrain window, then expose only the requested
     * core's globally addressed samples. The halo supplies one-step routing
     * evidence across the core boundary without treating the artificial core
     * edge as an ocean outlet. Routes at the outer halo boundary remain
     * unresolved, and independently sampled halos can disagree on long-range
     * drainage. The caller must reject conflicting routes when joining cores.
     */
    public record CoreWindow(Window sampled,List<HydrologyFlowGraphV2.Node> coreNodes) {
        public CoreWindow {
            Objects.requireNonNull(sampled);
            coreNodes=List.copyOf(coreNodes);
            if(coreNodes.isEmpty())throw new IllegalArgumentException("Empty core");
        }
    }

    public static CoreWindow generateCoreWithHalo(
            PlanetSurfaceV2 terrain,PlanetClimateV2 climate,
            int level,long firstColumn,long firstRow,int columns,int rows,
            int divisions,int haloRegions,double riverThreshold) {
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(climate);
        if(level<0||level>30||columns<1||rows<1||haloRegions<0||
           divisions<1||divisions>128||firstColumn<0||firstRow<0)
            throw new IllegalArgumentException("Invalid core window");
        long planetColumns=2L<<level,planetRows=1L<<level;
        long endColumn=Math.addExact(firstColumn,columns);
        long endRow=Math.addExact(firstRow,rows);
        if(endColumn>planetColumns||endRow>planetRows)
            throw new IllegalArgumentException("Core outside planet");
        long haloFirstColumn=Math.max(0,firstColumn-(long)haloRegions);
        long haloFirstRow=Math.max(0,firstRow-(long)haloRegions);
        long haloEndColumn=Math.min(planetColumns,Math.addExact(endColumn,haloRegions));
        long haloEndRow=Math.min(planetRows,Math.addExact(endRow,haloRegions));
        int haloColumns=Math.toIntExact(haloEndColumn-haloFirstColumn);
        int haloRows=Math.toIntExact(haloEndRow-haloFirstRow);
        var sampled=generate(terrain,climate,level,haloFirstColumn,haloFirstRow,
            haloColumns,haloRows,divisions,riverThreshold);
        long minX=Math.multiplyExact(firstColumn,divisions);
        long minY=Math.multiplyExact(firstRow,divisions);
        long maxX=Math.multiplyExact(endColumn,divisions);
        long maxY=Math.multiplyExact(endRow,divisions);
        var core=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:sampled.nodes()){
            var address=node.address();
            if(address.x()>=minX&&address.x()<=maxX&&
               address.y()>=minY&&address.y()<=maxY)
                core.add(node);
        }
        return new CoreWindow(sampled,core);
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
