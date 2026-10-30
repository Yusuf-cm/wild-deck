package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyDrainageAuditBindingV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),1));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),1));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),1));
        return sources;
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations heights() {
        return a->a.equals(at(0,2))||a.equals(at(4,2))?0:10;
    }

    @Test void rejectsAuditFromDifferentPreviousTopologyEvenWithIdenticalVertices() {
        var sources=planet();
        var old=HydrologySphericalRouteAtlasV2.compile(
            sources,heights(),Set.of(at(0,2)));
        var changed=HydrologySphericalAtlasRebuildAuditV2.rebuild(
            old,sources,heights(),Set.of(at(0,2),at(4,2)));
        var unrelatedPrevious=changed.rebuilt();
        assertEquals(old.catchments().keySet(),unrelatedPrevious.catchments().keySet());
        assertNotEquals(HydrologyDrainageSnapshotIdV2.of(old),
            HydrologyDrainageSnapshotIdV2.of(unrelatedPrevious));
        assertEquals(HydrologyDrainageSnapshotIdV2.of(old),changed.previousTopology());
        var inbox=new HydrologyEpochInboxV2().snapshot();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageTransitionReportV2.inspect(
                unrelatedPrevious,changed,inbox));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageTransitionGateV2.verifyPending(
                unrelatedPrevious,changed,inbox));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageLandingPlanV2.inspect(
                unrelatedPrevious,changed,inbox));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAtlasRebuildAuditV2.verifyReusableSlice(
                unrelatedPrevious,changed,
                unrelatedPrevious.ownedRegion(
                    new HydrologyRegionSchedulerV2.Region(0,0))));
        assertDoesNotThrow(()->HydrologyDrainageTransitionReportV2.inspect(
            old,changed,inbox));
    }
}
