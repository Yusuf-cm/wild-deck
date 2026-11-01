package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyGeneratedRegionProviderV2Test {
    @Test void generatesDeterministicSeededNodesFromRealTerrain(){
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var provider=new HydrologyGeneratedRegionProviderV2(
            terrain,new PlanetClimateV2(terrain),3,4,2);
        var region=new HydrologyRegionSchedulerV2.Region(2,2);
        var first=List.copyOf(provider.load(region));
        var second=List.copyOf(provider.load(region));
        assertEquals(25,first.size());
        assertEquals(first,second);
        assertTrue(first.stream().allMatch(n->n.localWater()>=0));
        assertThrows(IllegalArgumentException.class,()->
            provider.load(new HydrologyRegionSchedulerV2.Region(-1,2)));
    }

    @Test void rejectsInvalidParameters(){
        assertThrows(NullPointerException.class,()->
            new HydrologyGeneratedRegionProviderV2(null,null,1,4,1));
    }
}
