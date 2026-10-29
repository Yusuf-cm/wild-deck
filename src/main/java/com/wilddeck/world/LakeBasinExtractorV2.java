package com.wilddeck.world;

import java.util.*;

/**
 * Extracts connected flooded depressions from a finite priority-flood grid.
 * Equal spill surfaces are grouped by 8-neighbor connectivity. Every cell
 * contributes its own supplied horizontal area to the reservoir geometry.
 *
 * These basin IDs are mosaic-local: lakes intersecting a loaded boundary
 * need a wider solve before their global identity/capacity is authoritative.
 */
public final class LakeBasinExtractorV2 {
    private LakeBasinExtractorV2() {}

    public record Lake(int anchorIndex,List<Integer> cells,
                       LakeReservoirV2.Basin reservoir) {
        public Lake {
            cells=List.copyOf(cells);
            Objects.requireNonNull(reservoir);
            if(cells.isEmpty()||anchorIndex!=cells.get(0))
                throw new IllegalArgumentException("Invalid basin anchor");
        }
    }

    public static List<Lake> extract(LakeSpillV2.Result spill,
                                     double[] terrain,double[] cellArea) {
        Objects.requireNonNull(spill);
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(cellArea);
        int width=spill.width(),height=spill.height(),size=Math.multiplyExact(width,height);
        if(terrain.length!=size||cellArea.length!=size)
            throw new IllegalArgumentException("Invalid basin grid");
        double[] surface=spill.waterSurface();
        boolean[] wet=new boolean[size],seen=new boolean[size];
        for(int i=0;i<size;i++){
            if(!Double.isFinite(terrain[i])||!Double.isFinite(cellArea[i])||
               cellArea[i]<=0||surface[i]<terrain[i])
                throw new IllegalArgumentException("Invalid basin geometry");
            wet[i]=surface[i]>terrain[i];
        }
        var result=new ArrayList<Lake>();
        for(int start=0;start<size;start++){
            if(!wet[start]||seen[start])continue;
            double spillLevel=surface[start];
            var queue=new ArrayDeque<Integer>();
            var indices=new ArrayList<Integer>();
            seen[start]=true;queue.add(start);
            while(!queue.isEmpty()){
                int current=queue.remove();
                indices.add(current);
                int x=current%width,y=current/width;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    if(dx==0&&dy==0)continue;
                    int nx=x+dx,ny=y+dy;
                    if(nx<0||ny<0||nx>=width||ny>=height)continue;
                    int next=ny*width+nx;
                    if(!seen[next]&&wet[next]&&
                       Double.compare(surface[next],spillLevel)==0){
                        seen[next]=true;queue.add(next);
                    }
                }
            }
            Collections.sort(indices);
            double[] beds=new double[indices.size()],areas=new double[indices.size()];
            for(int i=0;i<indices.size();i++){
                int index=indices.get(i);
                beds[i]=terrain[index];areas[i]=cellArea[index];
            }
            result.add(new Lake(indices.get(0),indices,
                new LakeReservoirV2.Basin(beds,areas,spillLevel)));
        }
        return List.copyOf(result);
    }
}
