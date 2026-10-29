package com.wilddeck.world;

import java.util.*;

/**
 * Mutable per-site overlay. Initial geology is immutable and independent of
 * the overlay. Each successful extraction creates a replayable event.
 */
public final class WorldResourceLedger {
    public record Extraction(long sequence, long siteId,
                             PlanetSurfaceGenerator.Resource resource, long amount) {
        public Extraction {
            Objects.requireNonNull(resource);
            if(sequence<0||amount<=0)throw new IllegalArgumentException("Invalid extraction");
        }
    }
    private final Map<Long,EnumMap<PlanetSurfaceGenerator.Resource,Long>> remaining=new HashMap<>();
    private final List<Extraction> events=new ArrayList<>();
    private long sequence=0;

    public synchronized void register(long siteId, Map<PlanetSurfaceGenerator.Resource,Long> stock) {
        Objects.requireNonNull(stock);
        if(remaining.containsKey(siteId))throw new IllegalArgumentException("Site already registered");
        var amounts=new EnumMap<PlanetSurfaceGenerator.Resource,Long>(PlanetSurfaceGenerator.Resource.class);
        for(var resource:PlanetSurfaceGenerator.Resource.values()){
            long quantity=Objects.requireNonNull(stock.get(resource),"Missing resource");
            if(quantity<0)throw new IllegalArgumentException("Negative stock");
            amounts.put(resource,quantity);
        }
        remaining.put(siteId,amounts);
    }
    public synchronized long remaining(long siteId,PlanetSurfaceGenerator.Resource resource){
        return site(siteId).get(Objects.requireNonNull(resource));
    }
    public synchronized Extraction extract(long siteId,PlanetSurfaceGenerator.Resource resource,long amount) {
        Objects.requireNonNull(resource);
        if(amount<=0)throw new IllegalArgumentException("Amount must be positive");
        var stock=site(siteId);
        long available=stock.get(resource);
        if(amount>available)throw new IllegalArgumentException("Insufficient stock");
        if(sequence==Long.MAX_VALUE)throw new IllegalStateException("Event sequence exhausted");
        stock.put(resource,available-amount);
        var event=new Extraction(sequence++,siteId,resource,amount);
        events.add(event);
        return event;
    }
    public synchronized List<Extraction> events(){return List.copyOf(events);}
    public synchronized Map<PlanetSurfaceGenerator.Resource,Long> snapshot(long siteId){
        return Map.copyOf(site(siteId));
    }
    private EnumMap<PlanetSurfaceGenerator.Resource,Long> site(long id){
        var found=remaining.get(id);
        if(found==null)throw new IllegalArgumentException("Unknown site");
        return found;
    }
    public static WorldResourceLedger replay(Map<Long,Map<PlanetSurfaceGenerator.Resource,Long>> initial,
                                             List<Extraction> history){
        Objects.requireNonNull(initial);Objects.requireNonNull(history);
        var ledger=new WorldResourceLedger();
        initial.forEach(ledger::register);
        for(var event:history){
            Objects.requireNonNull(event);
            if(event.sequence()!=ledger.sequence)throw new IllegalArgumentException("Invalid event order");
            ledger.extract(event.siteId(),event.resource(),event.amount());
        }
        return ledger;
    }
}
