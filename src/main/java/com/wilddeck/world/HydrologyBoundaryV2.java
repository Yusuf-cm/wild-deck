package com.wilddeck.world;

import java.util.*;

/**
 * Export river discharge crossing a finite mosaic boundary. The global sample
 * address is stable across adjacent mosaics at the same level/resolution.
 * This is a transfer contract, not yet a globally solved water balance.
 */
public final class HydrologyBoundaryV2 {
    private HydrologyBoundaryV2(){}

    public record Crossing(GlobalHydrologyAddressV2.Sample from,
                           GlobalHydrologyAddressV2.Sample to,
                           double flow) {
        public Crossing {
            Objects.requireNonNull(from);Objects.requireNonNull(to);
            if(!Double.isFinite(flow)||flow<0)
                throw new IllegalArgumentException("Invalid flow");
        }
    }

    /**
     * Finds channel links whose destination lies on the mosaic perimeter.
     * A perimeter destination may still be an internal point of the wider
     * planet, but its flow is not automatically injected into another tile.
     */
    public static List<Crossing> arrivingAtPerimeter(RiverNetworkV2.Network rivers,
                                                      int level,int divisions,
                                                      long firstColumn,long firstRow){
        Objects.requireNonNull(rivers);
        int width=rivers.width(),height=rivers.height();
        if(width<2||height<2)throw new IllegalArgumentException("Invalid mosaic");
        var crossings=new ArrayList<Crossing>();
        for(var cell:rivers.channels()){
            int target=cell.downstream();
            if(target<0||target>=width*height)
                throw new IllegalArgumentException("Invalid downstream");
            int x=target%width,y=target/width;
            int fromX=cell.index()%width,fromY=cell.index()/width;
            if(x==0||x==width-1||y==0||y==height-1){
                if(fromX==0||fromX==width-1||fromY==0||fromY==height-1)
                    continue;
                crossings.add(new Crossing(
                    GlobalHydrologyAddressV2.at(level,divisions,firstColumn,firstRow,width,height,cell.index()),
                    GlobalHydrologyAddressV2.at(level,divisions,firstColumn,firstRow,width,height,target),
                    cell.flow()));
            }
        }
        return List.copyOf(crossings);
    }
}
