package com.wilddeck.world;

import java.util.*;

/**
 * Small card-independent world laboratory. Actions use properties and local
 * adjacency; environmental ticks and deliberate actor responses are separate.
 * Locations here are nearby sites, not continent-sized regions.
 */
public final class WorldSimulation {
    public enum Trait { FLAMMABLE, MOBILE, WATER_SOURCE, HEAT_SOURCE, FIREPROOF }
    public record Thing(String id,String name,String location,Set<Trait> traits,
                        int fuel,int health,int heatCharges) {
        public Thing { traits=Set.copyOf(traits); }
    }
    public record State(String id,String name,String location,Set<Trait> traits,
                        int fuel,int health,int heatCharges,int water,int wetTicks,boolean burning) {}
    public record Snapshot(int tick,List<State> things,Map<String,Integer> smoke,List<String> events) {}
    public record Result(boolean success,String message) {}

    private static final class Body {
        final String id,name;
        final Set<Trait> traits;
        String location;
        int fuel,health,heatCharges,water,wetTicks;
        boolean burning;
        Body(Thing t){id=t.id();name=t.name();location=t.location();traits=t.traits();
            fuel=t.fuel();health=t.health();heatCharges=t.heatCharges();}
        boolean has(Trait t){return traits.contains(t);}
        boolean livingActor(){return has(Trait.MOBILE)&&health>0;}
        State state(){return new State(id,name,location,traits,fuel,health,heatCharges,water,wetTicks,burning);}
    }
    private final WorldMap map;
    private final Map<String,Body> bodies=new LinkedHashMap<>();
    private final Map<String,Integer> smoke=new LinkedHashMap<>();
    private final List<String> events=new ArrayList<>();
    private int tick;

    public WorldSimulation(WorldMap map){this.map=Objects.requireNonNull(map);}
    public void add(Thing t){
        Objects.requireNonNull(t);
        if(t.id()==null||t.id().isBlank()||t.name()==null||t.name().isBlank()||
           map.location(t.location()).isEmpty()||t.fuel()<0||t.health()<0||t.heatCharges()<0||
           t.traits().contains(Trait.MOBILE)&&t.health()==0||
           t.heatCharges()>0&&!t.traits().contains(Trait.HEAT_SOURCE)||
           t.fuel()>0&&!t.traits().contains(Trait.FLAMMABLE))
            throw new IllegalArgumentException("Invalid world thing");
        if(bodies.putIfAbsent(t.id(),new Body(t))!=null)throw new IllegalArgumentException("Duplicate thing");
    }
    public State state(String id){
        Body b=bodies.get(id);if(b==null)throw new IllegalArgumentException("Unknown thing");return b.state();
    }
    public Snapshot snapshot(){
        return new Snapshot(tick,bodies.values().stream().map(Body::state).toList(),
            Collections.unmodifiableMap(new LinkedHashMap<>(smoke)),List.copyOf(events));
    }
    public boolean burningAt(String place){
        return bodies.values().stream().anyMatch(b->b.location.equals(place)&&b.burning);
    }
    private boolean reaches(Body source,Body target){
        return source.location.equals(target.location)||map.connected(source.location,target.location);
    }
    private Result fail(String reason){return new Result(false,reason);}
    private Result done(String message){events.add("T"+tick+": "+message);return new Result(true,message);}

    public Result ignite(String sourceId,String targetId){
        Body source=bodies.get(sourceId),target=bodies.get(targetId);
        if(source==null||target==null||source==target)return fail("Choose distinct existing source and target");
        if(!source.has(Trait.HEAT_SOURCE)||source.heatCharges==0||source.wetTicks>0||
           source.has(Trait.MOBILE)&&!source.livingActor())return fail("No usable heat source");
        if(!reaches(source,target))return fail("Heat source cannot reach target");
        if(!target.has(Trait.FLAMMABLE)||target.fuel==0||target.has(Trait.FIREPROOF))
            return fail("Target has no burnable fuel");
        if(target.burning)return fail("Target is already burning");
        if(target.wetTicks>0)return fail("Target is wet");
        source.heatCharges--;target.burning=true;
        return done(source.name+" ignited "+target.name);
    }
    public Result move(String actorId,String destination){
        Body actor=bodies.get(actorId);
        if(actor==null||!actor.livingActor())return fail("Only a living mobile actor can move");
        if(map.location(destination).isEmpty()||!map.connected(actor.location,destination))
            return fail("Destination must be adjacent");
        if(burningAt(destination)&&!actor.has(Trait.FIREPROOF))return fail("Fire blocks entry");
        String from=actor.location;actor.location=destination;
        return done(actor.name+" moved from "+from+" to "+destination);
    }
    public Result collectWater(String actorId,String sourceId){
        Body actor=bodies.get(actorId),source=bodies.get(sourceId);
        if(actor==null||!actor.livingActor())return fail("Water collection needs a living mobile actor");
        if(source==null||!source.has(Trait.WATER_SOURCE)||!actor.location.equals(source.location))
            return fail("Actor must be at a water source");
        if(actor.water==1)return fail("Actor already carries one water portion");
        actor.water=1;
        return done(actor.name+" collected water from "+source.name);
    }
    public Result douse(String actorId,String targetId){
        Body actor=bodies.get(actorId),target=bodies.get(targetId);
        if(actor==null||!actor.livingActor()||actor.water==0)return fail("A living actor must carry water");
        if(target==null||!reaches(actor,target))return fail("Water cannot reach target");
        if(!target.has(Trait.FLAMMABLE)||target.fuel==0)return fail("No remaining combustible material");
        if(!target.burning&&target.wetTicks>0)return fail("Target is already wet");
        actor.water--;target.burning=false;target.wetTicks=2;
        return done(actor.name+" doused "+target.name+"; wet for two ticks");
    }
    public void advance(){
        tick++;
        smoke.replaceAll((place,remaining)->remaining-1);smoke.values().removeIf(n->n<=0);
        Set<String> hotPlaces=new LinkedHashSet<>();
        for(Body b:bodies.values()){
            if(b.wetTicks>0)b.wetTicks--;
            if(!b.burning)continue;
            hotPlaces.add(b.location);b.fuel--;smoke.put(b.location,2);
            events.add("T"+tick+": "+b.name+" burned one fuel; smoke at "+b.location);
            if(b.fuel==0){b.burning=false;events.add("T"+tick+": "+b.name+" exhausted its fuel");}
        }
        // Exposure is once per site per tick, regardless of how many things burn.
        for(Body b:bodies.values())if(b.livingActor()&&hotPlaces.contains(b.location)&&!b.has(Trait.FIREPROOF)){
            b.health--;events.add("T"+tick+": "+b.name+" suffered one fire damage"+(b.health==0?"; incapacitated":""));
        }
    }
}
