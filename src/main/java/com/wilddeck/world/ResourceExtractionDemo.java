package com.wilddeck.world;

import java.util.Map;

/** Minimal deterministic extraction/replay smoke test. No paid services. */
public final class ResourceExtractionDemo {
    private ResourceExtractionDemo() {}
    public static void main(String[] args) {
        var generator=new PlanetSurfaceGenerator(Planet.emptyEarthSized(),42);
        var site=new WorldResourceDeposits.Site(17,new Planet.SurfacePoint(35,70));
        var initial=WorldResourceDeposits.initialStock(generator,site);
        var ledger=new WorldResourceLedger();
        ledger.register(site.id(),initial);
        var resource=PlanetSurfaceGenerator.Resource.IRON;
        long available=ledger.remaining(site.id(),resource);
        if(available>0)ledger.extract(site.id(),resource,Math.min(available,10));
        var replay=WorldResourceLedger.replay(Map.of(site.id(),initial),ledger.events());
        if(!ledger.snapshot(site.id()).equals(replay.snapshot(site.id())))
            throw new IllegalStateException("Replay diverged");
        System.out.println("World resource demo passed: "+ledger.snapshot(site.id())+
                           " events="+ledger.events().size());
    }
}
