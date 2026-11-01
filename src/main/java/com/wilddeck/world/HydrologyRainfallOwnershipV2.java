package com.wilddeck.world;

import java.util.Objects;

/**
 * Partitions rainfall at shared sample vertices across region tiles.
 * Ownership belongs to the lexicographically smallest adjacent region.
 * This avoids counting the same boundary sample twice when tiles are solved
 * separately. It does not itself resolve their inter-tile flow routing.
 */
public final class HydrologyRainfallOwnershipV2 {
    private HydrologyRainfallOwnershipV2(){}

    public static double[] ownedRainfall(double[] rainfall,int width,int height,
                                          int divisions,long firstColumn,long firstRow){
        Objects.requireNonNull(rainfall);
        if(divisions<1||width<2||height<2||
           (width-1)%divisions!=0||(height-1)%divisions!=0||
           rainfall.length!=(long)width*height||firstColumn<0||firstRow<0)
            throw new IllegalArgumentException("Invalid tiled mosaic");
        double[] result=rainfall.clone();
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int i=y*width+x;
            if(!Double.isFinite(result[i])||result[i]<0)
                throw new IllegalArgumentException("Invalid rainfall");
            // Left/up tile owns shared vertices, including tile corners.
            // On the global west/north edge there is no preceding tile.
            long globalX=Math.addExact(Math.multiplyExact(firstColumn,divisions),x);
            long globalY=Math.addExact(Math.multiplyExact(firstRow,divisions),y);
            if((x==0&&globalX>0)||(y==0&&globalY>0))
                result[i]=0;
        }
        return result;
    }
}
