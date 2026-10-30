package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyUnloadedSphericalTraceV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    @Test void crossesUnloadedRegionBoundariesAndResumesDeterministically() {
        // Level 1, divisions 2: x=2 and x=4 cross tile boundaries.
        // Only the starting vertex is notionally loaded by the caller.
        HydrologyGlobalSlopeRouterV2.Elevations heights=a->
            a.y()==2 ? 20-a.x() : 100;
        var outlets=Set.of(at(4,2));
        var complete=HydrologyUnloadedSphericalTraceV2.follow(
            at(1,2),heights,outlets,10);
        assertEquals(HydrologyUnloadedSphericalTraceV2.Status.VERIFIED_OUTLET,
            complete.status());
        assertEquals(List.of(at(1,2),at(2,2),at(3,2),at(4,2)),
            complete.visited());
        var first=HydrologyUnloadedSphericalTraceV2.follow(
            at(1,2),heights,outlets,2);
        assertEquals(HydrologyUnloadedSphericalTraceV2.Status.BUDGET_EXHAUSTED,
            first.status());
        assertEquals(List.of(at(1,2),at(2,2)),first.visited());
        assertEquals(at(3,2),first.next());
        var second=HydrologyUnloadedSphericalTraceV2.follow(
            first.next(),heights,outlets,2);
        assertEquals(HydrologyUnloadedSphericalTraceV2.Status.VERIFIED_OUTLET,
            second.status());
        var stitched=new ArrayList<>(first.visited());
        stitched.addAll(second.visited());
        assertEquals(complete.visited(),stitched);
    }

    @Test void wrapsLongitudeSeamWithoutDuplicatingItsSharedVertex() {
        HydrologyGlobalSlopeRouterV2.Elevations heights=a->{
            if(a.y()!=2)return 100;
            if(a.x()==0)return 10;
            if(a.x()==7)return 8;
            if(a.x()==6)return 6;
            if(a.x()==5)return 4;
            return 100;
        };
        var trace=HydrologyUnloadedSphericalTraceV2.follow(
            at(0,2),heights,Set.of(at(5,2)),10);
        assertEquals(HydrologyUnloadedSphericalTraceV2.Status.VERIFIED_OUTLET,
            trace.status());
        assertEquals(List.of(at(0,2),at(7,2),at(6,2),at(5,2)),
            trace.visited());
        assertEquals(trace.visited().size(),new HashSet<>(trace.visited()).size());
    }

    @Test void refusesToInventOutletForPitsOrIncompleteTraces() {
        HydrologyGlobalSlopeRouterV2.Elevations flat=a->10;
        var pit=HydrologyUnloadedSphericalTraceV2.follow(
            at(3,2),flat,Set.of(at(4,2)),5);
        assertEquals(HydrologyUnloadedSphericalTraceV2.Status.UNRESOLVED_PIT_OR_FLAT,
            pit.status());
        assertNull(pit.next());
        assertEquals(List.of(at(3,2)),pit.visited());
        var limited=HydrologyUnloadedSphericalTraceV2.follow(
            at(1,2),a->a.y()==2?20-a.x():100,Set.of(at(4,2)),1);
        assertEquals(HydrologyUnloadedSphericalTraceV2.Status.BUDGET_EXHAUSTED,
            limited.status());
        assertEquals(at(2,2),limited.next());
    }

    @Test void rejectsNoncanonicalOutletsMixedResolutionAndInvalidBudget() {
        HydrologyGlobalSlopeRouterV2.Elevations heights=a->10;
        assertThrows(IllegalArgumentException.class,()->
            HydrologyUnloadedSphericalTraceV2.follow(
                at(8,2),heights,Set.of(),5));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyUnloadedSphericalTraceV2.follow(
                at(1,2),heights,Set.of(at(8,2)),5));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyUnloadedSphericalTraceV2.follow(
                at(1,2),heights,
                Set.of(new GlobalHydrologyAddressV2.Sample(2,2,4,2)),5));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyUnloadedSphericalTraceV2.follow(
                at(1,2),heights,Set.of(),0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyUnloadedSphericalTraceV2.follow(
                at(1,2),a->Double.NaN,Set.of(),5));
    }
}
