package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyCatchmentChangeV2Test {
    private static GlobalHydrologyAddressV2.Sample at(int x,int y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    @Test void detectsChangedMouthAndExactlyWhichRiverReachMoved() {
        var a=at(4,4); var b=at(5,5); var c=at(6,6); var d=at(7,6);
        var old=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        old.put(a,b); old.put(b,c); old.put(c,null); old.put(d,null);
        var next=new HashMap<>(old); next.put(b,d);
        var first=HydrologyGlobalCatchmentsV2.resolve(old,Set.of(c,d));
        var second=HydrologyGlobalCatchmentsV2.resolve(next,Set.of(c,d));
        var change=HydrologyCatchmentChangeV2.compare(first,second);
        assertEquals(Set.of(a,b),change.reassignedVertices());
        assertEquals(Set.of(first.reaches().get(b)),change.removedReaches());
        assertEquals(Set.of(second.reaches().get(b)),change.addedReaches());
        assertEquals(change,HydrologyCatchmentChangeV2.compare(first,second));
    }
    @Test void unchangedDrainageHasEmptyChangeSet() {
        var a=at(4,4); var b=at(5,5);
        var graph=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        graph.put(a,b); graph.put(b,null);
        var resolved=HydrologyGlobalCatchmentsV2.resolve(graph,Set.of(b));
        assertEquals(true,HydrologyCatchmentChangeV2.compare(resolved,resolved).unchanged());
    }
    @Test void cannotCompareIncompatiblePhysicalOwnershipWithoutReconciliation() {
        var a=at(4,4); var b=at(5,5); var c=at(6,6);
        var old=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        old.put(a,b); old.put(b,null);
        var next=new HashMap<>(old); next.put(c,null);
        var first=HydrologyGlobalCatchmentsV2.resolve(old,Set.of(b));
        var second=HydrologyGlobalCatchmentsV2.resolve(next,Set.of(b,c));
        assertThrows(IllegalArgumentException.class,()->HydrologyCatchmentChangeV2.compare(first,second));
    }
}
