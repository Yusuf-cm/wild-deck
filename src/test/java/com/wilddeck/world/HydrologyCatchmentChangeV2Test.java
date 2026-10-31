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
    @Test void equivalentGeographyAcrossSamplingLevelsIsNotAFalseMigration() {
        var source = at(4,4);
        var mouthA = at(6,6);
        var mouthB = at(7,6);
        var fineSource = new GlobalHydrologyAddressV2.Sample(3,4,8,8);
        var fineMouthA = new GlobalHydrologyAddressV2.Sample(3,4,12,12);
        var fineMouthB = new GlobalHydrologyAddressV2.Sample(3,4,14,12);
        var beforeEdges = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        beforeEdges.put(source,mouthA);
        beforeEdges.put(mouthA,null);
        beforeEdges.put(mouthB,null);
        var afterEdges = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        afterEdges.put(fineSource,fineMouthA);
        afterEdges.put(fineMouthA,null);
        afterEdges.put(fineMouthB,null);
        var before = HydrologyGlobalCatchmentsV2.resolve(beforeEdges,Set.of(mouthA,mouthB));
        var after = HydrologyGlobalCatchmentsV2.resolve(afterEdges,Set.of(fineMouthA,fineMouthB));
        assertTrue(HydrologyCatchmentChangeV2.compare(before,after).unchanged());

        afterEdges.put(fineSource,fineMouthB);
        var migrated = HydrologyGlobalCatchmentsV2.resolve(afterEdges,Set.of(fineMouthA,fineMouthB));
        var change = HydrologyCatchmentChangeV2.compare(before,migrated);
        assertEquals(Set.of(source),change.reassignedVertices());
        assertEquals(Set.of(before.reaches().get(source)),change.removedReaches());
        assertEquals(Set.of(migrated.reaches().get(fineSource)),change.addedReaches());
    }

    @Test void differentGeographicCoverageStillFailsAcrossLevels() {
        var source = at(4,4);
        var mouth = at(6,6);
        var beforeEdges = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        beforeEdges.put(source,mouth);
        beforeEdges.put(mouth,null);
        var fineSource = new GlobalHydrologyAddressV2.Sample(3,4,8,8);
        var otherMouth = new GlobalHydrologyAddressV2.Sample(3,4,14,12);
        var afterEdges = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        afterEdges.put(fineSource,otherMouth);
        afterEdges.put(otherMouth,null);
        var before = HydrologyGlobalCatchmentsV2.resolve(beforeEdges,Set.of(mouth));
        var after = HydrologyGlobalCatchmentsV2.resolve(afterEdges,Set.of(otherMouth));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyCatchmentChangeV2.compare(before,after));
    }

}
