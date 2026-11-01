package com.wilddeck.world;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable review file embeds Java-generated maps, never a second JS generator. */
public final class PlanetAtlasExporter {
    private PlanetAtlasExporter(){}
    public static void main(String[] args)throws Exception{
        Path destination=Path.of(args.length==0?"target/planet-atlas":args[0]);
        Files.createDirectories(destination);
        var maps=new ArrayList<String>();
        for(long seed:new long[]{42,1598,-7}){
            long started=System.nanoTime();
            var atlas=new PlanetSurfaceGenerator(Planet.emptyEarthSized(),seed).atlas(360);
            maps.add("\""+seed+"\":"+atlas.toJson());
            Files.writeString(destination.resolve("seed-"+seed+".json"),atlas.toJson(),StandardCharsets.UTF_8);
            System.out.printf(Locale.ROOT,"Seed %d: land %.1f%%; iron %d, copper %d, stormglass %d samples; %.2fs%n",
                seed,atlas.landFraction()*100,count(atlas.iron()),count(atlas.copper()),count(atlas.stormglass()),
                (System.nanoTime()-started)/1e9);
        }
        String embedded="window.ATLAS_PRESETS={"+String.join(",",maps)+"};";
        Files.writeString(destination.resolve("wild-deck-planet-atlas.html"),
            PlanetAtlasServer.template().replace("/*ATLAS_EMBED*/",embedded),StandardCharsets.UTF_8);
        System.out.println("Portable atlas written to "+destination.toAbsolutePath());
    }
    private static long count(int[] values){return Arrays.stream(values).filter(v->v>0).count();}
}
