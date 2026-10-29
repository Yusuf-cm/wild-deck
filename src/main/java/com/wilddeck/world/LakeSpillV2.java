package com.wilddeck.world;

import java.util.*;

/**
 * Priority-flood depression filling on a finite rectangular terrain grid.
 * Boundary cells act as outlets. This provides lake surfaces and spill paths
 * within the grid, not a globally resolved planet-wide ocean/lake model.
 */
public final class LakeSpillV2 {
    private LakeSpillV2(){}
    private record Cell(int index,double level){}
    public record Result(int width,int height,double[] waterSurface,int[] spillTo) {
        public Result {
            if(width<1||height<1||waterSurface.length!=width*height||spillTo.length!=width*height)
                throw new IllegalArgumentException("Invalid spill grid");
            waterSurface=waterSurface.clone();spillTo=spillTo.clone();
        }
        @Override public double[] waterSurface(){return waterSurface.clone();}
        @Override public int[] spillTo(){return spillTo.clone();}
        public double waterDepth(int x,int y,double[] terrain){
            if(terrain.length!=width*height||x<0||y<0||x>=width||y>=height)
                throw new IllegalArgumentException("Invalid depth request");
            return Math.max(0,waterSurface[y*width+x]-terrain[y*width+x]);
        }
    }
    public static Result fill(int width,int height,double[] terrain){
        if(width<1||height<1)throw new IllegalArgumentException("Invalid dimensions");
        int size=Math.multiplyExact(width,height);
        Objects.requireNonNull(terrain);
        if(terrain.length!=size)throw new IllegalArgumentException("Invalid terrain size");
        for(double elevation:terrain)if(!Double.isFinite(elevation))
            throw new IllegalArgumentException("Invalid elevation");
        var queue=new PriorityQueue<Cell>(Comparator.comparingDouble(Cell::level).thenComparingInt(Cell::index));
        boolean[] seen=new boolean[size];
        double[] surface=new double[size];
        int[] spill=new int[size];Arrays.fill(spill,-1);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            if(x!=0&&y!=0&&x!=width-1&&y!=height-1)continue;
            int i=y*width+x;
            seen[i]=true;surface[i]=terrain[i];queue.add(new Cell(i,terrain[i]));
        }
        while(!queue.isEmpty()){
            var cell=queue.remove();
            int x=cell.index()%width,y=cell.index()/width;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                if(dx==0&&dy==0)continue;
                int nx=x+dx,ny=y+dy;
                if(nx<0||ny<0||nx>=width||ny>=height)continue;
                int i=ny*width+nx;
                if(seen[i])continue;
                seen[i]=true;
                surface[i]=Math.max(terrain[i],cell.level());
                spill[i]=cell.index();
                queue.add(new Cell(i,surface[i]));
            }
        }
        return new Result(width,height,surface,spill);
    }
}
