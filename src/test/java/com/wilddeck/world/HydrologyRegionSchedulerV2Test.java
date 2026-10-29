package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRegionSchedulerV2Test {
    @Test void overlapsAreDeduplicatedAndOrderIsStable(){
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var a=new HydrologyRegionSchedulerV2.Region(0,0);
        var b=new HydrologyRegionSchedulerV2.Region(1,0);
        var loaded=HydrologyRegionSchedulerV2.load(List.of(b,a),1,12,r->{
            visited.add(r);
            return List.of();
        });
        assertEquals(12,loaded.size());
        assertEquals(12,new HashSet<>(visited).size());
        assertEquals(new HydrologyRegionSchedulerV2.Region(-1,-1),visited.get(0));
        assertEquals(new HydrologyRegionSchedulerV2.Region(2,1),visited.get(11));
        var sorted=new ArrayList<>(visited);
        Collections.sort(sorted);
        assertEquals(sorted,visited);
    }
    @Test void budgetFailsBeforeAnyProviderLoads(){
        var count=new int[1];
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRegionSchedulerV2.load(
                List.of(new HydrologyRegionSchedulerV2.Region(0,0)),2,20,r->{
                    count[0]++;
                    return List.of();
                }));
        assertEquals(0,count[0]);
    }
    @Test void rejectsOverflowAndInvalidLimits(){
        assertThrows(ArithmeticException.class,()->
            HydrologyRegionSchedulerV2.load(
                List.of(new HydrologyRegionSchedulerV2.Region(Long.MAX_VALUE,0)),1,9,
                r->List.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRegionSchedulerV2.load(List.of(),-1,5,r->List.of()));
    }
}
