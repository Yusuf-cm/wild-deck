package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldResourceLedgerTest {
    private static final PlanetSurfaceGenerator.Resource IRON=PlanetSurfaceGenerator.Resource.IRON;
    private static final PlanetSurfaceGenerator.Resource COPPER=PlanetSurfaceGenerator.Resource.COPPER;
    private static final PlanetSurfaceGenerator.Resource STORMGLASS=PlanetSurfaceGenerator.Resource.STORMGLASS;
    private Map<PlanetSurfaceGenerator.Resource,Long> stock(long iron){
        return Map.of(IRON,iron,COPPER,0L,STORMGLASS,0L);
    }
    @Test void extractionConsumesFiniteResourcesAndCannotOverdraw(){
        var ledger=new WorldResourceLedger();ledger.register(5,stock(100));
        ledger.extract(5,IRON,40);
        assertEquals(60,ledger.remaining(5,IRON));
        assertThrows(IllegalArgumentException.class,()->ledger.extract(5,IRON,61));
        assertEquals(60,ledger.remaining(5,IRON));
        assertEquals(1,ledger.events().size());
    }
    @Test void replayReconstructsTheExactSameState(){
        var ledger=new WorldResourceLedger();ledger.register(5,stock(100));
        ledger.extract(5,IRON,30);ledger.extract(5,IRON,20);
        var replay=WorldResourceLedger.replay(Map.of(5L,stock(100)),ledger.events());
        assertEquals(ledger.snapshot(5),replay.snapshot(5));
        assertEquals(ledger.events(),replay.events());
    }
    @Test void invalidEventSequenceIsRejected(){
        assertThrows(IllegalArgumentException.class,()->WorldResourceLedger.replay(
            Map.of(5L,stock(100)),List.of(new WorldResourceLedger.Extraction(2,5,IRON,1))));
    }
    @Test void duplicateSitesAndInvalidStockAreRejected(){
        var ledger=new WorldResourceLedger();ledger.register(5,stock(10));
        assertThrows(IllegalArgumentException.class,()->ledger.register(5,stock(10)));
        assertThrows(IllegalArgumentException.class,()->ledger.register(6,Map.of(IRON,-1L,COPPER,0L,STORMGLASS,0L)));
        assertThrows(IllegalArgumentException.class,()->ledger.extract(5,IRON,0));
    }
    @Test void initialDepositsAreSeededAndReproducible(){
        var terrain=new PlanetSurfaceGenerator(Planet.emptyEarthSized(),42);
        var site=new WorldResourceDeposits.Site(8,new Planet.SurfacePoint(35,70));
        assertEquals(WorldResourceDeposits.initialStock(terrain,site),
                     WorldResourceDeposits.initialStock(terrain,site));
        assertTrue(WorldResourceDeposits.initialStock(terrain,site).values().stream().allMatch(x->x>=0));
    }
}
