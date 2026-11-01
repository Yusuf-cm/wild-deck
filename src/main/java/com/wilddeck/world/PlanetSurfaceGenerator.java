package com.wilddeck.world;

import java.util.*;

/**
 * Versioned, stateless sampling on a sphere. Geometry is sampled in 3D so the
 * longitude seam and poles do not introduce terrain seams. No match state,
 * climate simulation, extraction or kingdom knowledge is stored here.
 */
public final class PlanetSurfaceGenerator {
    public static final String VERSION="planet-surface-v1";
    public enum Resource { IRON, COPPER, STORMGLASS }
    public record Sample(int elevationMeters,Map<Resource,Integer> potentialPercent) {
        public Sample {potentialPercent=Map.copyOf(potentialPercent);}
        public boolean land(){return elevationMeters>0;}
    }
    private final Planet planet;
    private final long seed,landKey,detailKey,ridgeKey,geologyKey;
    private final long[] warpKeys=new long[3],resourceKeys=new long[3];

    public PlanetSurfaceGenerator(Planet planet,long seed){
        this.planet=Objects.requireNonNull(planet);this.seed=seed;
        landKey=layer(1);detailKey=layer(2);ridgeKey=layer(3);geologyKey=layer(4);
        for(int i=0;i<3;i++){warpKeys[i]=layer(10+i);resourceKeys[i]=layer(20+i);}
    }
    public long seed(){return seed;}
    public Planet planet(){return planet;}
    private long layer(int index){return mix(seed^((long)index*0x9E3779B97F4A7C15L));}
    private static long mix(long value){
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return value^(value>>>31);
    }
    private static double lattice(long key,int x,int y,int z){
        long bits=mix(key^((long)x*0x9E3779B97F4A7C15L)^((long)y*0xD1B54A32D192ED03L)^((long)z*0x94D049BB133111EBL));
        return (bits>>>11)*0x1.0p-53*2-1;
    }
    private static double fade(double t){return t*t*t*(t*(t*6-15)+10);}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double noise(long key,double x,double y,double z){
        int ix=(int)StrictMath.floor(x),iy=(int)StrictMath.floor(y),iz=(int)StrictMath.floor(z);
        double fx=fade(x-ix),fy=fade(y-iy),fz=fade(z-iz);
        double near=lerp(lerp(lattice(key,ix,iy,iz),lattice(key,ix+1,iy,iz),fx),
                         lerp(lattice(key,ix,iy+1,iz),lattice(key,ix+1,iy+1,iz),fx),fy);
        double far=lerp(lerp(lattice(key,ix,iy,iz+1),lattice(key,ix+1,iy,iz+1),fx),
                        lerp(lattice(key,ix,iy+1,iz+1),lattice(key,ix+1,iy+1,iz+1),fx),fy);
        return lerp(near,far,fz);
    }
    private static double fractal(long key,double x,double y,double z,int octaves){
        double sum=0,weight=1,total=0;
        for(int i=0;i<octaves;i++){
            sum+=noise(mix(key+i),x,y,z)*weight;total+=weight;
            x*=2;y*=2;z*=2;weight*=.5;
        }
        return sum/total;
    }
    private static double clamp(double x){return Math.max(0,Math.min(1,x));}
    public Sample sample(Planet.SurfacePoint point){
        Objects.requireNonNull(point);
        double lat=StrictMath.toRadians(point.latitudeDeg()),lon=StrictMath.toRadians(point.longitudeDeg());
        double x=StrictMath.cos(lat)*StrictMath.cos(lon),y=StrictMath.cos(lat)*StrictMath.sin(lon),z=StrictMath.sin(lat);
        if(Math.abs(point.latitudeDeg())==90){x=0;y=0;z=Math.copySign(1,point.latitudeDeg());}
        double wx=x+.25*fractal(warpKeys[0],x*1.4,y*1.4,z*1.4,2);
        double wy=y+.25*fractal(warpKeys[1],x*1.4,y*1.4,z*1.4,2);
        double wz=z+.25*fractal(warpKeys[2],x*1.4,y*1.4,z*1.4,2);
        double continents=fractal(landKey,wx*1.7,wy*1.7,wz*1.7,4)-.035;
        double detail=fractal(detailKey,x*11,y*11,z*11,3);
        double ridges=StrictMath.pow(1-Math.abs(noise(ridgeKey,x*6,y*6,z*6)),4);
        double elevation=(continents+.035*detail)*15000;
        if(elevation>0)elevation+=ridges*3000*clamp(continents/.2);
        int meters=(int)Math.round(Math.max(-11000,Math.min(9000,elevation)));
        Map<Resource,Integer> resources=new EnumMap<>(Resource.class);
        double geology=noise(geologyKey,x*5,y*5,z*5);
        for(Resource resource:Resource.values()){
            boolean suitable=switch(resource){
                case IRON -> meters>300&&geology>-.15;
                case COPPER -> meters>30&&meters<3000&&geology<.22;
                case STORMGLASS -> meters>1000&&geology>.05;
            };
            int index=resource.ordinal();double frequency=index==2?10:13;
            double deposit=fractal(resourceKeys[index],x*frequency,y*frequency,z*frequency,2);
            double threshold=index==2?.48:.10;
            int potential=suitable?(int)Math.round(100*clamp((deposit-threshold)*3)):0;
            resources.put(resource,potential);
        }
        return new Sample(meters,resources);
    }
    public Atlas atlas(int width){
        if(width!=180&&width!=360&&width!=720)
            throw new IllegalArgumentException("Atlas width must be 180, 360 or 720");
        int height=width/2,total=width*height;
        int[] elevation=new int[total],iron=new int[total],copper=new int[total],stormglass=new int[total];
        double landWeight=0,totalWeight=0;
        for(int row=0;row<height;row++){
            double latitude=90-(row+.5)*180/height;
            double weight=StrictMath.cos(StrictMath.toRadians(latitude));
            for(int col=0;col<width;col++){
                int index=row*width+col;
                Sample sample=sample(new Planet.SurfacePoint(latitude,-180+(col+.5)*360/width));
                elevation[index]=sample.elevationMeters();iron[index]=sample.potentialPercent().get(Resource.IRON);
                copper[index]=sample.potentialPercent().get(Resource.COPPER);stormglass[index]=sample.potentialPercent().get(Resource.STORMGLASS);
                totalWeight+=weight;if(sample.land())landWeight+=weight;
            }
        }
        return new Atlas(seed,width,height,planet.radiusKm(),landWeight/totalWeight,elevation,iron,copper,stormglass);
    }
    /** Coarse inspection data, not a tile-based world or finite resource inventory. */
    public record Atlas(long seed,int width,int height,double radiusKm,double landFraction,
                        int[] elevation,int[] iron,int[] copper,int[] stormglass) {
        public Atlas {
            elevation=elevation.clone();iron=iron.clone();copper=copper.clone();stormglass=stormglass.clone();
        }
        @Override public int[] elevation(){return elevation.clone();}
        @Override public int[] iron(){return iron.clone();}
        @Override public int[] copper(){return copper.clone();}
        @Override public int[] stormglass(){return stormglass.clone();}
        public String toJson(){
            return "{\"generatorVersion\":\""+VERSION+"\",\"seed\":\""+seed+"\",\"width\":"+width+
                ",\"height\":"+height+",\"radiusKm\":"+radiusKm+",\"landFraction\":"+landFraction+
                ",\"elevation\":"+Arrays.toString(elevation)+",\"iron\":"+Arrays.toString(iron)+
                ",\"copper\":"+Arrays.toString(copper)+",\"stormglass\":"+Arrays.toString(stormglass)+"}";
        }
    }
}
