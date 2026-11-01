package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyGlobalCatchmentsV2Test {
    private static GlobalHydrologyAddressV2.Sample at(int level, int divisions, long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(level, divisions, x, y);
    }

    @Test void crossRegionConfluenceHasOneVerifiedMouthAndDeterministicReaches() {
        var a = at(2,4,4,4);
        var b = at(2,4,5,3);
        var c = at(2,4,5,4);
        var unloaded = at(2,4,6,5);
        var mouth = at(2,4,7,6);
        var edges = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        edges.put(b,c); edges.put(mouth,null); edges.put(a,c);
        edges.put(unloaded,mouth); edges.put(c,unloaded);
        var result = HydrologyGlobalCatchmentsV2.resolve(edges,Set.of(mouth));
        assertEquals(5,result.catchments().size());
        assertEquals(4,result.reaches().size());
        assertEquals(1,new HashSet<>(result.catchments().values()).size());
        assertEquals(result.catchments().get(a),result.catchments().get(b));
        var reversed = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        var keys = new ArrayList<>(edges.keySet());
        Collections.reverse(keys);
        for (var key:keys) reversed.put(key,edges.get(key));
        assertEquals(result,HydrologyGlobalCatchmentsV2.resolve(reversed,Set.of(mouth)));
        assertThrows(UnsupportedOperationException.class, () -> result.catchments().clear());
    }

    @Test void globalMouthAndReachIdsAreResolutionIndependent() {
        var coarse = at(2,4,4,4);
        var coarseMouth = at(2,4,6,6);
        var fine = at(3,4,8,8);
        var fineMouth = at(3,4,12,12);
        var coarseEdges = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        coarseEdges.put(coarse,coarseMouth); coarseEdges.put(coarseMouth,null);
        var fineEdges = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        fineEdges.put(fine,fineMouth); fineEdges.put(fineMouth,null);
        var first = HydrologyGlobalCatchmentsV2.resolve(coarseEdges,Set.of(coarseMouth));
        var second = HydrologyGlobalCatchmentsV2.resolve(fineEdges,Set.of(fineMouth));
        assertEquals(first.catchments().get(coarse),second.catchments().get(fine));
        assertEquals(first.reaches().get(coarse),second.reaches().get(fine));
        assertEquals(first.catchments().get(coarse).stableKey(),
                     second.catchments().get(fine).stableKey());
        var duplicate = new LinkedHashMap<>(coarseEdges);
        duplicate.put(fine,fineMouth); duplicate.put(fineMouth,null);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(duplicate,Set.of(coarseMouth,fineMouth)));
    }

    @Test void geographicSeamAndPolarAliasesCannotOwnWaterTwice() {
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyGlobalCatchmentsV2.coordinate(at(2,4,32,8)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyGlobalCatchmentsV2.coordinate(at(2,4,2,0)));
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(at(2,4,0,0)),
                     HydrologyGlobalCatchmentsV2.coordinate(at(3,4,0,0)));
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(at(2,4,0,8)),
                     HydrologyGlobalCatchmentsV2.coordinate(at(3,4,0,16)));
    }

    @Test void rejectsUnknownUnloadedFrontiersUnverifiedMouthsAndCycles() {
        var a=at(2,4,4,4);
        var b=at(2,4,5,5);
        var c=at(2,4,6,6);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(Map.of(a,b),Set.of(a)));
        var missing=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        missing.put(a,b); missing.put(c,null);
        assertThrows(IllegalStateException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(missing,Set.of(c)));
        var cycle=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        cycle.put(a,b); cycle.put(b,a); cycle.put(c,null);
        assertThrows(IllegalStateException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(cycle,Set.of(c)));
        var extraTerminal=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        extraTerminal.put(a,null); extraTerminal.put(b,null);
        assertThrows(IllegalStateException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(extraTerminal,Set.of(a)));
    }

    @Test void longCrossResolutionAliasChainResolvesIterativelyWithoutRecursion() {
        var graph = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        int length = 20_000;
        for (int x=1;x<=length;x++) {
            var current=at(16,4,x,100);
            // Every edge refers to the same physical vertex at finer resolution.
            // Only the coarse sample is an owner; the alias never doubles water.
            graph.put(current,x==length ? null : at(17,4,2L*(x+1),200));
        }
        var mouth=at(16,4,length,100);
        var resolved=HydrologyGlobalCatchmentsV2.resolve(graph,Set.of(mouth));
        assertEquals(length,resolved.catchments().size());
        assertEquals(length-1,resolved.reaches().size());
        assertEquals(resolved.catchments().get(mouth),
                     resolved.catchments().get(at(16,4,1,100)));
    }

    @Test void changedRiverMouthChangesIdentityWithoutRewritingStableGeography() {
        var source=at(2,4,4,4);
        var junction=at(2,4,5,5);
        var oldMouth=at(2,4,6,6);
        var newMouth=at(2,4,7,6);
        var old=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        old.put(source,junction); old.put(junction,oldMouth);
        old.put(oldMouth,null); old.put(newMouth,null);
        var next=new HashMap<>(old);
        next.put(junction,newMouth);
        var before=HydrologyGlobalCatchmentsV2.resolve(old,Set.of(oldMouth,newMouth));
        var after=HydrologyGlobalCatchmentsV2.resolve(next,Set.of(oldMouth,newMouth));
        assertNotEquals(before.catchments().get(source),after.catchments().get(source));
        assertEquals(before.reaches().get(source),after.reaches().get(source));
        assertNotEquals(before.reaches().get(junction),after.reaches().get(junction));
    }
    @Test void crossResolutionDownstreamAliasesUseExactlyOnePhysicalOwner() {
        var source = at(2,4,4,4);
        var junction = at(2,4,5,4);
        var junctionFineAlias = at(3,4,10,8);
        var unloadedFine = at(3,4,12,10);
        var mouth = at(3,4,14,12);
        var graph = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        graph.put(source,junctionFineAlias);
        graph.put(junction,unloadedFine);
        graph.put(unloadedFine,mouth);
        graph.put(mouth,null);
        var resolved = HydrologyGlobalCatchmentsV2.resolve(graph,Set.of(mouth));
        assertEquals(4,resolved.catchments().size());
        assertEquals(3,resolved.reaches().size());
        assertEquals(resolved.catchments().get(source),resolved.catchments().get(mouth));
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(junction),
                     resolved.reaches().get(source).downstream());
        assertFalse(resolved.catchments().containsKey(junctionFineAlias));
        var reversed = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        var keys = new ArrayList<>(graph.keySet());
        Collections.reverse(keys);
        for (var key : keys) reversed.put(key,graph.get(key));
        assertEquals(resolved,HydrologyGlobalCatchmentsV2.resolve(reversed,Set.of(mouth)));
    }

    @Test void crossResolutionAliasesCannotDuplicateWaterOrHideCyclesAndMissingFrontiers() {
        var source = at(2,4,4,4);
        var junction = at(2,4,5,4);
        var alias = at(3,4,10,8);
        var mouth = at(2,4,6,6);
        var graph = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        graph.put(source,alias);
        graph.put(junction,mouth);
        graph.put(mouth,null);
        var duplicate = new LinkedHashMap<>(graph);
        duplicate.put(alias,mouth);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(duplicate,Set.of(mouth)));
        var selfLoop = new LinkedHashMap<>(graph);
        selfLoop.put(junction,alias);
        assertThrows(IllegalStateException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(selfLoop,Set.of(mouth)));
        var missing = new LinkedHashMap<>(graph);
        missing.put(junction,at(3,4,11,9));
        assertThrows(IllegalStateException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(missing,Set.of(mouth)));
        var seam = new LinkedHashMap<>(graph);
        seam.put(junction,at(2,4,32,8));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyGlobalCatchmentsV2.resolve(seam,Set.of(mouth)));
    }

}
