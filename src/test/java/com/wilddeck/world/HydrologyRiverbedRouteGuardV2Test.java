package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverbedRouteGuardV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(3,3);
    private static final GlobalHydrologyAddressV2.Sample B=at(4,3);
    private static final GlobalHydrologyAddressV2.Sample C=at(5,3);
    private static final GlobalHydrologyAddressV2.Sample D=at(3,4);
    private static final GlobalHydrologyAddressV2.Sample HALO=at(3,2);

    private static HydrologySphericalLakeBasinsV2.Plan plan() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
    }
    private static HydrologyTerrainOverlayV2.State old() {
        return new HydrologyTerrainOverlayV2.State("old",Map.of());
    }
    private static HydrologyTerrainOverlayV2.State edited() {
        return new HydrologyTerrainOverlayV2.State("new",Map.of(A,29.0,B,21.0));
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return at -> at.equals(A)?30:at.equals(B)?20:at.equals(C)?10:100;
    }

    @Test void unchangedSteepestRoutesPassAndCheckUnloadedHalo() {
        var report=HydrologyRiverbedRouteGuardV2.verify(plan(),old(),edited(),seed());
        assertEquals(2,report.changedVertices());
        assertEquals(2,report.checkedLoaded());
        assertTrue(report.checkedUnloaded()>0);
        assertEquals(B,HydrologySphericalTopologyV2.route(
            List.of(new HydrologyGlobalSlopeRouterV2.Source(A,0)),
            HydrologyTerrainOverlayV2.elevations(seed(),edited()),
            Set.of()).get(0).downstream());
    }

    @Test void localCompetingNeighborRejectsEvenWhenOldDirectedEdgeRemainsDownhill() {
        var competitor=(HydrologyGlobalSlopeRouterV2.Elevations) at ->
            at.equals(D)?19.8:seed().at(at);
        assertEquals(B,HydrologySphericalTopologyV2.route(
            List.of(new HydrologyGlobalSlopeRouterV2.Source(A,0)),
            HydrologyTerrainOverlayV2.elevations(competitor,old()),
            Set.of()).get(0).downstream());
        assertEquals(D,HydrologySphericalTopologyV2.route(
            List.of(new HydrologyGlobalSlopeRouterV2.Source(A,0)),
            HydrologyTerrainOverlayV2.elevations(competitor,edited()),
            Set.of()).get(0).downstream());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedRouteGuardV2.verify(plan(),old(),edited(),competitor));
    }

    @Test void unloadedHaloCannotSilentlyRerouteIntoChangedBed() {
        var halo=(HydrologyGlobalSlopeRouterV2.Elevations) at ->
            at.equals(HALO)?60:seed().at(at);
        assertEquals(B,HydrologySphericalTopologyV2.route(
            List.of(new HydrologyGlobalSlopeRouterV2.Source(HALO,0)),
            HydrologyTerrainOverlayV2.elevations(halo,old()),
            Set.of()).get(0).downstream());
        assertEquals(A,HydrologySphericalTopologyV2.route(
            List.of(new HydrologyGlobalSlopeRouterV2.Source(HALO,0)),
            HydrologyTerrainOverlayV2.elevations(halo,edited()),
            Set.of()).get(0).downstream());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedRouteGuardV2.verify(plan(),old(),edited(),halo));
    }

    @Test void rejectsUnownedTerrainEditsAndUnverifiedExistingRoute() {
        var outside=new HydrologyTerrainOverlayV2.State("new",Map.of(D,101.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedRouteGuardV2.verify(plan(),old(),outside,seed()));
        var stale=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,null,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedRouteGuardV2.verify(stale,old(),edited(),seed()));
    }

    @Test void unchangedElevationIsSafeNoopEvenWithDifferentWorldLabel() {
        var same=new HydrologyTerrainOverlayV2.State("new",Map.of());
        var result=HydrologyRiverbedRouteGuardV2.verify(plan(),old(),same,seed());
        assertEquals(new HydrologyRiverbedRouteGuardV2.Report(0,0,0),result);
    }

    @Test void lakeSpillwayEditRequiresSeparateVerifiedDrainageTransition() {
        var lake=new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,C,
            new LakeReservoirV2.Basin(new double[]{20},new double[]{1},25));
        var withLake=new HydrologySphericalLakeBasinsV2.Plan(
            plan().routes(),List.of(lake));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedRouteGuardV2.verify(withLake,old(),edited(),seed()));
    }
}
