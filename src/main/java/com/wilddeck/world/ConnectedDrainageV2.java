package com.wilddeck.world;

import java.util.*;

/**
 * Routes downhill flow across a rectangular mosaic of equally sized regions.
 * Shared vertices are sampled only once in a common coordinate lattice.
 * This is a finite regional mosaic, NOT a whole-planet hydrology solver.
 */
public final class ConnectedDrainageV2 {
    private ConnectedDrainageV2(){}
    public record Mosaic(int width,int height,RegionalDrainageV2.Drainage drainage,
                         double[] elevation,double[] rainfall) {
        public Mosaic {
            if(width<1||height<1||drainage.width()!=width||drainage.height()!=height||
               elevation.length!=width*height||rainfall.length!=width*height)
                throw new IllegalArgumentException("Invalid mosaic");
            elevation=elevation.clone();rainfall=rainfall.clone();
        }
        @Override public double[] elevation(){return elevation.clone();}
        @Override public double[] rainfall(){return rainfall.clone();}
    }
    public static Mosaic generate(PlanetSurfaceV2 world,PlanetClimateV2 climate,
                                  int level,long firstColumn,long firstRow,
                                  int columns,int rows,int divisions){
        Objects.requireNonNull(world);Objects.requireNonNull(climate);
        if(columns<1||rows<1||divisions<1||divisions>128)
            throw new IllegalArgumentException("Invalid mosaic dimensions");
        long planetColumns=2L<<level,planetRows=1L<<level;
        if(level<0||level>30||firstColumn<0||firstRow<0||
           firstColumn+columns>planetColumns||firstRow+rows>planetRows)
            throw new IllegalArgumentException("Mosaic outside planet");
        int width=Math.addExact(Math.multiplyExact(columns,divisions),1);
        int height=Math.addExact(Math.multiplyExact(rows,divisions),1);
        if((long)width*height>1_000_000)throw new IllegalArgumentException("Mosaic too large");
        double[] elevation=new double[width*height],rainfall=new double[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int tileX=Math.min(x/divisions,columns-1),tileY=Math.min(y/divisions,rows-1);
            int localX=x-tileX*divisions,localY=y-tileY*divisions;
            var region=new PlanetRegionGrid.Region(level,firstColumn+tileX,firstRow+tileY);
            var point=PlanetRegionGrid.point(region,localX,localY,divisions);
            int i=y*width+x;
            elevation[i]=world.sample(point).elevationMeters();
            rainfall[i]=climate.sample(point).moisture();
        }
        return new Mosaic(width,height,RegionalDrainageV2.route(width,height,elevation,rainfall),
                          elevation,rainfall);
    }
}
