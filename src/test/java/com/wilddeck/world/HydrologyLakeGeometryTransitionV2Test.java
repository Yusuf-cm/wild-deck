package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeGeometryTransitionV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUTLET=at(0,2);
    private static final GlobalHydrologyAddressV2.Sample PIT=at(4,2);
    private static List<HydrologyGlobalSlopeRouterV2.Source> sphere() {
        var result=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            result.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return result;
    }
    private static HydrologySphericalLakeBasinsV2.Plan plan(double pitBed) {
        return HydrologySphericalLakeBasinsV2.extract(sphere(),
            s->s.equals(OUTLET)?0:s.equals(PIT)?pitBed:10,
            Set.of(OUTLET),1000);
    }

    @Test void erodedLakeExpandsWithoutCreatingWater() {
        var before=plan(-5);
        var after=plan(-10);
        var anchor=before.lakes().get(0).anchor();
        double stored=before.lakes().get(0).reservoir().capacity()/2;
        var result=HydrologyLakeGeometryTransitionV2.reconcile(
            new HydrologyLakeStateV2.State("world",Map.of(anchor,stored)),
            before,after);
        assertEquals(stored,result.initialStorage(),1e-9);
        assertEquals(stored,result.finalStorage(),1e-9);
        assertEquals(0,result.released(),0);
        assertTrue(result.overflow().isEmpty());
        assertEquals(stored,result.state().volumes().get(anchor),1e-9);
        assertEquals("world",result.state().world());
    }

    @Test void sedimentFilledLakeReleasesExcessToNewVerifiedSpill() {
        var before=plan(-10);
        var after=plan(-5);
        var oldLake=before.lakes().get(0);
        var newLake=after.lakes().get(0);
        double stored=oldLake.reservoir().capacity();
        double newCapacity=newLake.reservoir().capacity();
        var result=HydrologyLakeGeometryTransitionV2.reconcile(
            new HydrologyLakeStateV2.State("world",
                Map.of(oldLake.anchor(),stored)),before,after);
        assertEquals(stored,result.initialStorage(),1e-9);
        assertEquals(newCapacity,result.finalStorage(),1e-9);
        assertEquals(stored-newCapacity,result.released(),1e-9);
        assertEquals(result.released(),
            result.overflow().get(newLake.spillTarget()),1e-9);
        assertEquals(stored,result.finalStorage()+result.released(),1e-9);
        assertEquals(result.state(),HydrologyLakeStateV2.decode(
            HydrologyLakeStateV2.encode(result.state())));
    }

    @Test void refusesWetBasinDisappearanceOrUnverifiedPriorStorage() {
        var before=plan(-5);
        var dry=plan(10);
        var lake=before.lakes().get(0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeGeometryTransitionV2.reconcile(
                new HydrologyLakeStateV2.State("world",Map.of(
                    lake.anchor(),lake.reservoir().capacity()/2)),before,dry));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeGeometryTransitionV2.reconcile(
                new HydrologyLakeStateV2.State("world",Map.of(
                    lake.anchor(),lake.reservoir().capacity()+1)),before,before));
        var empty=HydrologyLakeGeometryTransitionV2.reconcile(
            new HydrologyLakeStateV2.State("world",Map.of(lake.anchor(),0.0)),
            before,dry);
        assertTrue(empty.state().volumes().isEmpty());
        assertEquals(0,empty.released(),0);
    }
}
