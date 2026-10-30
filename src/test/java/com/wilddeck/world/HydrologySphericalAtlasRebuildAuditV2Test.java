package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalAtlasRebuildAuditV2Test {
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

    @Test void unchangedTerrainPreservesEveryRouteAndCatchment() {
        var sources=planet();
        var outlet=at(0,2);
        var original=HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(outlet)?0:10,Set.of(outlet));
        var audit=HydrologySphericalAtlasRebuildAuditV2.rebuild(
            original,sources,a->a.equals(outlet)?0:10,Set.of(outlet));
        assertTrue(audit.changedRoutes().isEmpty());
        assertTrue(audit.changedCatchments().isEmpty());
        assertTrue(audit.changedReaches().isEmpty());
        assertTrue(audit.affectedSamples().isEmpty());
        assertEquals(original.catchments(),audit.rebuilt().catchments());
        var slice=original.ownedRegion(new HydrologyRegionSchedulerV2.Region(0,0));
        assertFalse(slice.isEmpty());
        assertDoesNotThrow(()->HydrologySphericalAtlasRebuildAuditV2
            .verifyReusableSlice(original,audit,slice));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAtlasRebuildAuditV2
                .verifyReusableSlice(original,audit,List.of()));
        var duplicated=new ArrayList<>(slice);
        duplicated.add(slice.get(0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAtlasRebuildAuditV2
                .verifyReusableSlice(original,audit,duplicated));
    }

    @Test void newVerifiedOutletInvalidatesUpstreamAndMatchesFullRebuild() {
        var sources=planet();
        var first=at(0,2);
        var second=at(4,2);
        HydrologyGlobalSlopeRouterV2.Elevations heights=a->
            a.equals(first)||a.equals(second)?0:10;
        var previous=HydrologySphericalRouteAtlasV2.compile(
            sources,heights,Set.of(first));
        var audit=HydrologySphericalAtlasRebuildAuditV2.rebuild(
            previous,sources,heights,Set.of(first,second));
        var direct=HydrologySphericalRouteAtlasV2.compile(
            sources,heights,Set.of(first,second));
        assertEquals(direct.routes(),audit.rebuilt().routes());
        assertEquals(direct.catchments(),audit.rebuilt().catchments());
        assertEquals(direct.reaches(),audit.rebuilt().reaches());
        assertTrue(audit.changedRoutes().contains(second));
        assertTrue(audit.changedCatchments().contains(second));
        assertFalse(audit.changedReaches().isEmpty());
        // A geometrically unchanged edge can acquire a different outlet.
        // Its reach ID is unchanged, but its catchment assignment is not.
        for(var entry:previous.reaches().entrySet()) {
            var nextCatchment=audit.rebuilt().reaches().get(entry.getKey());
            if(nextCatchment!=null&&!nextCatchment.equals(entry.getValue()))
                assertTrue(audit.changedReaches().contains(entry.getKey()));
        }
        var invalidated=previous.routes().stream()
            .filter(n->audit.affectedSamples().contains(n.address()))
            .findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAtlasRebuildAuditV2
                .verifyReusableSlice(previous,audit,List.of(invalidated)));
        assertTrue(audit.affectedSamples().containsAll(audit.changedRoutes()));
        assertTrue(audit.affectedSamples().containsAll(audit.changedCatchments()));
        assertTrue(audit.affectedSamples().size()>=audit.changedCatchments().size());
        // Every upstream dependency in either epoch must be invalidated,
        // including dependencies crossing region boundaries and the seam.
        for(var graph:List.of(previous.routes(),audit.rebuilt().routes()))
            for(var node:graph)
                if(node.downstream()!=null &&
                   audit.affectedSamples().contains(node.downstream()))
                    assertTrue(audit.affectedSamples().contains(node.address()));

        var solved=HydrologyFlowGraphV2.solve(audit.rebuilt().routes());
        assertEquals(0,HydrologyConservationV2.verify(
            audit.rebuilt().routes(),solved).residual(),1e-10);
        var shuffled=new ArrayList<>(sources);
        Collections.shuffle(shuffled,new Random(33));
        var replay=HydrologySphericalAtlasRebuildAuditV2.rebuild(
            previous,shuffled,heights,Set.of(second,first));
        assertEquals(audit.changedRoutes(),replay.changedRoutes());
        assertEquals(audit.changedCatchments(),replay.changedCatchments());
        assertEquals(audit.changedReaches(),replay.changedReaches());
        assertEquals(audit.affectedSamples(),replay.affectedSamples());
    }

    @Test void sedimentRidgeReroutesWithoutInventingCatchmentOrWater() {
        var sources=planet();
        var outlet=at(0,2);
        // A three-cell sediment ridge blocks the former shortest spill corridor.
        HydrologyGlobalSlopeRouterV2.Elevations before=a->
            a.equals(outlet)?0:10;
        HydrologyGlobalSlopeRouterV2.Elevations after=a->
            a.equals(outlet)?0:(a.x()==1&&a.y()>=1&&a.y()<=3)?100:10;
        var previous=HydrologySphericalRouteAtlasV2.compile(
            sources,before,Set.of(outlet));
        var audit=HydrologySphericalAtlasRebuildAuditV2.rebuild(
            previous,sources,after,Set.of(outlet));
        var direct=HydrologySphericalRouteAtlasV2.compile(
            sources,after,Set.of(outlet));
        assertEquals(direct.routes(),audit.rebuilt().routes());
        assertEquals(direct.catchments(),audit.rebuilt().catchments());
        assertEquals(previous.catchments(),audit.rebuilt().catchments());
        assertFalse(audit.changedRoutes().isEmpty());
        assertFalse(audit.changedReaches().isEmpty());
        assertTrue(audit.affectedSamples().containsAll(audit.changedRoutes()));
        var solved=HydrologyFlowGraphV2.solve(audit.rebuilt().routes());
        assertEquals(26,solved.terminalOutflow().get(outlet),1e-10);
        assertEquals(0,HydrologyConservationV2.verify(
            audit.rebuilt().routes(),solved).residual(),1e-10);
    }

    @Test void refusesIncompletePlanetAndDoesNotConfuseRainfallWithTopology() {
        var sources=planet();
        var outlet=at(0,2);
        HydrologyGlobalSlopeRouterV2.Elevations heights=a->a.equals(outlet)?0:10;
        var previous=HydrologySphericalRouteAtlasV2.compile(
            sources,heights,Set.of(outlet));
        var wet=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        for(var source:sources)
            wet.add(new HydrologyGlobalSlopeRouterV2.Source(
                source.address(),source.localWater()+2));
        var audit=HydrologySphericalAtlasRebuildAuditV2.rebuild(
            previous,wet,heights,Set.of(outlet));
        assertTrue(audit.changedRoutes().isEmpty());
        assertTrue(audit.changedCatchments().isEmpty());
        assertTrue(audit.changedReaches().isEmpty());
        assertTrue(audit.affectedSamples().isEmpty());
        var partial=new ArrayList<>(sources);
        partial.remove(0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAtlasRebuildAuditV2.rebuild(
                previous,partial,heights,Set.of(outlet)));
    }
}
