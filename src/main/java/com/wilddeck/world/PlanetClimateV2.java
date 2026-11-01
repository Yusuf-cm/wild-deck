package com.wilddeck.world;

import java.util.Objects;

/**
 * Deterministic first-pass planetary climate classification. All calculations
 * are continuous on the sphere; this is NOT a seasonal weather simulation,
 * atmospheric circulation model, river network, or erosion solver.
 */
public final class PlanetClimateV2 {
    public enum Biome { OCEAN, ICE, TUNDRA, DESERT, GRASSLAND, FOREST, RAINFOREST, ALPINE }
    public record Climate(double temperatureC, double moisture, Biome biome) {
        public Climate {
            if(!Double.isFinite(temperatureC)||!Double.isFinite(moisture)||moisture<0||moisture>1)
                throw new IllegalArgumentException("Invalid climate");
            Objects.requireNonNull(biome);
        }
    }
    private final PlanetSurfaceV2 terrain;
    private final long moistureKey;

    public PlanetClimateV2(PlanetSurfaceV2 terrain) {
        this.terrain=Objects.requireNonNull(terrain);
        this.moistureKey=terrain.layerSeed(0x434C494D415445L);
    }
    private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    private static long mix(long value){
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return value^(value>>>31);
    }
    private static double lattice(long seed,int x,int y,int z){
        long bits=mix(seed^((long)x*0x9E3779B97F4A7C15L)^((long)y*0xD1B54A32D192ED03L)^((long)z*0x94D049BB133111EBL));
        return (bits>>>11)*0x1.0p-53;
    }
    private static double fade(double t){return t*t*(3-2*t);}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private double noise(double x,double y,double z){
        int ix=(int)StrictMath.floor(x),iy=(int)StrictMath.floor(y),iz=(int)StrictMath.floor(z);
        double fx=fade(x-ix),fy=fade(y-iy),fz=fade(z-iz);
        double a=lerp(lerp(lattice(moistureKey,ix,iy,iz),lattice(moistureKey,ix+1,iy,iz),fx),
                      lerp(lattice(moistureKey,ix,iy+1,iz),lattice(moistureKey,ix+1,iy+1,iz),fx),fy);
        double b=lerp(lerp(lattice(moistureKey,ix,iy,iz+1),lattice(moistureKey,ix+1,iy,iz+1),fx),
                      lerp(lattice(moistureKey,ix,iy+1,iz+1),lattice(moistureKey,ix+1,iy+1,iz+1),fx),fy);
        return lerp(a,b,fz);
    }
    public Climate sample(Planet.SurfacePoint point){
        Objects.requireNonNull(point);
        var surface=terrain.sample(point);
        double lat=StrictMath.toRadians(point.latitudeDeg());
        double lon=StrictMath.toRadians(point.longitudeDeg());
        double x=StrictMath.cos(lat)*StrictMath.cos(lon);
        double y=StrictMath.cos(lat)*StrictMath.sin(lon);
        double z=StrictMath.sin(lat);
        if(Math.abs(point.latitudeDeg())==90){x=0;y=0;z=Math.copySign(1,point.latitudeDeg());}
        double elevation=Math.max(0,surface.elevationMeters());
        double temperature=30-55*StrictMath.pow(Math.abs(point.latitudeDeg())/90,1.35)-0.0065*elevation;
        double subtropicalDry=StrictMath.exp(-StrictMath.pow((Math.abs(point.latitudeDeg())-28)/15,2));
        double moisture=clamp(.32+.42*noise(x*4,y*4,z*4)-.30*subtropicalDry);
        Biome biome;
        if(surface.elevationMeters()<=0)biome=Biome.OCEAN;
        else if(temperature<-12)biome=Biome.ICE;
        else if(elevation>3000)biome=Biome.ALPINE;
        else if(temperature<0)biome=Biome.TUNDRA;
        else if(moisture<.22)biome=Biome.DESERT;
        else if(moisture<.43)biome=Biome.GRASSLAND;
        else if(temperature>20&&moisture>.67)biome=Biome.RAINFOREST;
        else biome=Biome.FOREST;
        return new Climate(temperature,moisture,biome);
    }
}
