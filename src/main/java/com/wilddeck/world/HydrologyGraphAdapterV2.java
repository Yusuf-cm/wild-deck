package com.wilddeck.world;

import java.util.*;

/**
 * Bridges a finite priority-flood mosaic to the global-address flow graph.
 * Boundary outlets on land are unresolved, not falsely labelled ocean mouths.
 * Optional externally verified one-step links can continue routes into a
 * neighboring mosaic, including a currently unloaded one.
 *
 * Each mosaic contains its shared vertices exactly once. When combining
 * overlapping mosaics, deduplicate at the source (or the flow graph rejects
 * the duplicate) rather than injecting the same rainfall twice.
 */
public final class HydrologyGraphAdapterV2 {
    private HydrologyGraphAdapterV2() {}

    public static List<HydrologyFlowGraphV2.Node> nodes(
            RegionalHydrologyV2.Hydrology hydrology,
            int level,int divisions,long firstColumn,long firstRow) {
        return nodes(hydrology,level,divisions,firstColumn,firstRow,Map.of());
    }

    public static List<HydrologyFlowGraphV2.Node> nodes(
            RegionalHydrologyV2.Hydrology hydrology,
            int level,int divisions,long firstColumn,long firstRow,
            Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> outgoing) {
        Objects.requireNonNull(hydrology);
        Objects.requireNonNull(outgoing);
        var mosaic=hydrology.terrain();
        int width=mosaic.width(),height=mosaic.height();
        if(divisions<1 || (width-1)%divisions!=0 || (height-1)%divisions!=0)
            throw new IllegalArgumentException("Incompatible mosaic resolution");
        double[] rain=mosaic.rainfall(),elevation=mosaic.elevation();
        int[] next=hydrology.lakes().spillTo();
        if(next.length!=rain.length)throw new IllegalArgumentException("Mismatched spill grid");
        var result=new ArrayList<HydrologyFlowGraphV2.Node>(rain.length);
        var used=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(int i=0;i<rain.length;i++){
            var address=GlobalHydrologyAddressV2.at(
                level,divisions,firstColumn,firstRow,width,height,i);
            int x=i%width,y=i/width;
            boolean boundary=x==0||y==0||x==width-1||y==height-1;
            var external=outgoing.get(address);
            if(external!=null){
                if(!boundary||next[i]>=0||elevation[i]<=0 ||
                   external.level()!=level||external.divisions()!=divisions||
                   Math.abs(external.x()-address.x())>1||
                   Math.abs(external.y()-address.y())>1||
                   external.equals(address))
                    throw new IllegalArgumentException("Invalid external boundary route");
                used.add(address);
            }
            var downstream=next[i]>=0
                ? GlobalHydrologyAddressV2.at(
                    level,divisions,firstColumn,firstRow,width,height,next[i])
                : external;
            // An edge with no known onward route is a pending frontier
            // unless it is at or below sea level.
            boolean terminal=downstream==null && elevation[i]<=0;
            result.add(new HydrologyFlowGraphV2.Node(
                address,rain[i],downstream,terminal));
        }
        if(used.size()!=outgoing.size())
            throw new IllegalArgumentException("External routes contain unknown samples");
        return List.copyOf(result);
    }
}
