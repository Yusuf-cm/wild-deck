package com.wilddeck.world;

import java.util.Arrays;
import java.util.Objects;

/**
 * Seam-consistent heightfield mesh for a small region of the planet.
 * Uses authoritative planet samples; no local random seed or invented terrain.
 * This is geometry data, not a renderer, cave model, or editable voxel volume.
 */
public final class LocalTerrainMesh {
    private LocalTerrainMesh() {}

    public record Mesh(PlanetRegionGrid.Region region, int divisions,
                       double[] latitudeDeg, double[] longitudeDeg, double[] elevationMeters) {
        public Mesh {
            Objects.requireNonNull(region);
            if (divisions < 1 || divisions > 256) throw new IllegalArgumentException("Invalid divisions");
            int expected = (divisions + 1) * (divisions + 1);
            if (latitudeDeg.length != expected || longitudeDeg.length != expected || elevationMeters.length != expected)
                throw new IllegalArgumentException("Invalid vertex count");
            latitudeDeg = latitudeDeg.clone();
            longitudeDeg = longitudeDeg.clone();
            elevationMeters = elevationMeters.clone();
            for (int i=0;i<expected;i++) {
                if (!Double.isFinite(latitudeDeg[i]) || !Double.isFinite(longitudeDeg[i]) ||
                    !Double.isFinite(elevationMeters[i])) throw new IllegalArgumentException("Invalid vertex");
            }
        }
        @Override public double[] latitudeDeg() { return latitudeDeg.clone(); }
        @Override public double[] longitudeDeg() { return longitudeDeg.clone(); }
        @Override public double[] elevationMeters() { return elevationMeters.clone(); }
        public int vertexCount() { return elevationMeters.length; }
        public int triangleCount() { return divisions * divisions * 2; }
        public int index(int x,int y) {
            if(x<0||y<0||x>divisions||y>divisions)throw new IllegalArgumentException("Invalid vertex index");
            return y*(divisions+1)+x;
        }
        public double elevationAt(int x,int y){return elevationMeters[index(x,y)];}
        public int[] triangleIndices() {
            int[] indices=new int[triangleCount()*3];
            int cursor=0;
            for(int y=0;y<divisions;y++)for(int x=0;x<divisions;x++){
                int a=index(x,y),b=index(x+1,y),c=index(x,y+1),d=index(x+1,y+1);
                indices[cursor++]=a;indices[cursor++]=c;indices[cursor++]=b;
                indices[cursor++]=b;indices[cursor++]=c;indices[cursor++]=d;
            }
            return indices;
        }
    }

    public static Mesh generate(PlanetSurfaceV2 world, PlanetRegionGrid.Region region, int divisions) {
        Objects.requireNonNull(world);Objects.requireNonNull(region);
        if(divisions<1||divisions>256)throw new IllegalArgumentException("Invalid divisions");
        int count=(divisions+1)*(divisions+1);
        double[] lat=new double[count],lon=new double[count],elevation=new double[count];
        for(int y=0;y<=divisions;y++)for(int x=0;x<=divisions;x++){
            int index=y*(divisions+1)+x;
            var point=PlanetRegionGrid.point(region,x,y,divisions);
            var sample=world.sample(point);
            lat[index]=point.latitudeDeg();lon[index]=point.longitudeDeg();
            elevation[index]=sample.elevationMeters();
        }
        return new Mesh(region,divisions,lat,lon,elevation);
    }
}
