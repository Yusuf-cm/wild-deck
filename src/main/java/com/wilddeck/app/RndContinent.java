package com.wilddeck.app;

import java.util.*;

/**
 * Shared continent for a four-kingdom match. Locations are public and stable;
 * hidden cards and private orders stay in their owner's dashboard.
 * Geography is deliberately separate from card effects until movement is implemented.
 */
public final class RndContinent {
 public record Region(String id,String name,String terrain,String resource,String owner,List<String> borders){}
 private static final List<Region> REGIONS=List.of(
  new Region("N1","Moonwood","Forest","Timber","asha",List.of("N2","C1")),
  new Region("N2","Iron Peaks","Mountain","Iron","brian",List.of("N1","C1","E1")),
  new Region("C1","Crossroads","Plains","Trade","neutral",List.of("N1","N2","W1","E1","S1")),
  new Region("W1","Runebank","Highland","Mana","player",List.of("C1","S1")),
  new Region("E1","Ash Coast","Coast","Obsidian","mira",List.of("N2","C1","S1")),
  new Region("S1","Sunken Ruins","Ruins","Relics","neutral",List.of("W1","C1","E1"))
 );
 private RndContinent(){}
 public static List<Region> regions(){return REGIONS;}
 public static String capital(String kingdom){
  return switch(kingdom){
   case "player"->"Runebank";case "asha"->"Moonwood";
   case "brian"->"Iron Peaks";case "mira"->"Ash Coast";
   default->"Unknown";
  };
 }
 public static String map(){
  return """
                  [Moonwood: Asha]----[Iron Peaks: Brian]
                        \\                  /     |
                      [Crossroads: Neutral]-------+
                        /         \\             /
          [Runebank: Player]  [Sunken Ruins]  [Ash Coast: Mira]
         """;
 }
 public static String atlas(){
  StringBuilder out=new StringBuilder("CONTINENT ATLAS (geography only; travel not yet resolved)\\n");
  for(Region r:REGIONS)out.append(r.id()).append("  ").append(r.name())
    .append("  ").append(r.terrain()).append("  ").append(r.resource())
    .append("  owner=").append(r.owner()).append("  borders=").append(r.borders()).append('\\n');
  return out.toString();
 }
}
