package com.wilddeck.world;

import java.util.*;

/**
 * Transfers boundary fluxes by globally stable sample address.
 * Incoming water is injected at the shared boundary sample; its rainfall
 * must be owned by exactly one mosaic to prevent double counting.
 * This helper does not independently solve global routing.
 */
public final class HydrologyFluxTransferV2 {
    private HydrologyFluxTransferV2(){}

    public static double[] incoming(int level,int divisions,long firstColumn,long firstRow,
                                    int width,int height,
                                    Collection<HydrologyBoundaryV2.Crossing> crossings){
        Objects.requireNonNull(crossings);
        if(width<2||height<2||((long)width*height)>1_000_000)
            throw new IllegalArgumentException("Invalid mosaic");
        long originX=Math.multiplyExact(firstColumn,divisions);
        long originY=Math.multiplyExact(firstRow,divisions);
        double[] incoming=new double[width*height];
        for(var crossing:crossings){
            Objects.requireNonNull(crossing);
            var target=crossing.to();
            if(target.level()!=level||target.divisions()!=divisions)
                throw new IllegalArgumentException("Incompatible sampling resolution");
            long x=target.x()-originX,y=target.y()-originY;
            if(x<0||x>=width||y<0||y>=height)
                continue; // Crossing is for another region.
            if(x!=0&&y!=0&&x!=width-1&&y!=height-1)
                throw new IllegalArgumentException("Flux must enter on boundary");
            int index=(int)(y*width+x);
            incoming[index]+=crossing.flow();
            if(!Double.isFinite(incoming[index]))
                throw new IllegalArgumentException("Incoming discharge overflow");
        }
        return incoming;
    }
}
