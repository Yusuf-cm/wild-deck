package com.wilddeck.world;

import java.util.*;
import static com.wilddeck.world.WorldSimulation.Trait.*;

/** Repeatable world experiment, with no cards, AI service, server or credentials. */
public final class WorldSandboxDemo {
    public static WorldSimulation sample(){
        var map=new WorldMap(List.of(
            new WorldLocation("forest","Forest clearing",Set.of("FOREST")),
            new WorldLocation("river","River bank",Set.of("WATER")),
            new WorldLocation("settlement","Settlement",Set.of("SETTLEMENT"))),
            Map.of("forest",Set.of("river","settlement"),"river",Set.of("settlement")));
        var w=new WorldSimulation(map);
        w.add(new WorldSimulation.Thing("trees","Dry trees","forest",Set.of(FLAMMABLE),3,0,0));
        w.add(new WorldSimulation.Thing("gate","Wooden gate","settlement",Set.of(FLAMMABLE),2,0,0));
        w.add(new WorldSimulation.Thing("wall","Stone wall","settlement",Set.of(),0,0,0));
        w.add(new WorldSimulation.Thing("water","River water","river",Set.of(WATER_SOURCE),0,0,0));
        w.add(new WorldSimulation.Thing("ember","Ember supply","river",Set.of(HEAT_SOURCE),0,0,4));
        w.add(new WorldSimulation.Thing("scout","Scout","forest",Set.of(MOBILE),0,3,0));
        w.add(new WorldSimulation.Thing("worker","Worker","river",Set.of(MOBILE),0,3,0));
        return w;
    }
    public static void main(String[] args){
        var w=sample();
        System.out.println("EXPERIMENT: fire -> environmental reaction -> chosen responses");
        System.out.println(w.ignite("ember","trees").message());w.advance();
        System.out.println(w.move("worker","forest").message());
        System.out.println(w.move("scout","river").message());
        System.out.println(w.collectWater("worker","water").message());
        System.out.println(w.douse("worker","trees").message());
        System.out.println(w.move("worker","forest").message());
        System.out.println("Scout health: "+w.state("scout").health()+"; remaining tree fuel: "+w.state("trees").fuel());
        System.out.println(w.ignite("ember","trees").message());
        System.out.println("SAME RULE, DIFFERENT MATERIAL OBJECT:");
        System.out.println(w.ignite("ember","gate").message());
        System.out.println(w.ignite("ember","wall").message());
        System.out.println("EVENTS:");w.snapshot().events().forEach(System.out::println);
    }
}
