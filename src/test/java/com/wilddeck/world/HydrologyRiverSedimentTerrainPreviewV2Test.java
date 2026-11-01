package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverSedimentTerrainPreviewV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A=at(0,2),B=at(1,2),C=at(2,2),OTHER=at(3,2);

    private static HydrologySphericalLakeBasinsV2.Plan plan() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return a->a.equals(A)?10:a.equals(B)?8:a.equals(C)?0:20;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> eroded() {
        return Map.of(A,2000.0,B,0.0,C,0.0);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> capacity() {
        return Map.of(A,1000.0,B,0.0,C,100.0);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> area() {
        return Map.of(A,1.0,B,1.0,C,1.0);
    }

    @Test void riverCutAndDepositionProduceConservativeElevationPreview() {
        var previous=new HydrologyTerrainOverlayV2.State("world",Map.of(OTHER,17.0));
        var preview=HydrologyRiverSedimentTerrainPreviewV2.preview(
            previous,seed(),plan(),eroded(),capacity(),area(),2000);
        assertEquals(9.5,preview.candidate().elevations().get(A),1e-12);
        assertEquals(8.5,preview.candidate().elevations().get(B),1e-12);
        assertEquals(17,preview.candidate().elevations().get(OTHER),1e-12);
        assertEquals(2000,preview.erodedMass(),1e-12);
        assertEquals(2000,preview.depositedMass(),1e-12);
        assertEquals(0,preview.sediment().budget().residual(),1e-12);
        assertEquals(Map.of(OTHER,17.0),previous.elevations());
        assertEquals("world",preview.candidate().world());
    }

    @Test void changedRiverSlopeMustRemainDownhill() {
        var mass=Map.of(A,6000.0,B,0.0,C,0.0);
        var cap=Map.of(A,6000.0,B,0.0,C,100.0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTerrainPreviewV2.preview(
                new HydrologyTerrainOverlayV2.State("x",Map.of()),
                seed(),plan(),mass,cap,area(),1000));
    }

    @Test void unknownFrontierAndChangedTerminalAreRejected() {
        var partial=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false)),List.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTerrainPreviewV2.preview(
                new HydrologyTerrainOverlayV2.State("x",Map.of()),
                seed(),partial,Map.of(A,2.0),Map.of(A,1.0),
                Map.of(A,1.0),2000));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTerrainPreviewV2.preview(
                new HydrologyTerrainOverlayV2.State("x",Map.of()),
                seed(),plan(),Map.of(A,0.0,B,0.0,C,1.0),
                Map.of(A,10.0,B,10.0,C,0.0),area(),2000));
    }

    @Test void invalidAreaDensityOrSourceCoverageFails() {
        var state=new HydrologyTerrainOverlayV2.State("x",Map.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTerrainPreviewV2.preview(
                state,seed(),plan(),eroded(),capacity(),area(),0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTerrainPreviewV2.preview(
                state,seed(),plan(),eroded(),capacity(),
                Map.of(A,1.0,B,0.0,C,1.0),2000));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTerrainPreviewV2.preview(
                state,seed(),plan(),eroded(),capacity(),
                Map.of(A,1.0,B,1.0),2000));
    }

    @Test void mapAndRouteOrderingDoNotAffectCandidate() {
        var routes=new ArrayList<>(plan().routes());
        Collections.reverse(routes);
        var reordered=new HydrologySphericalLakeBasinsV2.Plan(routes,List.of());
        var other=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        other.put(C,0.0);other.put(B,0.0);other.put(A,2000.0);
        var state=new HydrologyTerrainOverlayV2.State("x",Map.of());
        var a=HydrologyRiverSedimentTerrainPreviewV2.preview(
            state,seed(),plan(),eroded(),capacity(),area(),2000);
        var b=HydrologyRiverSedimentTerrainPreviewV2.preview(
            state,seed(),reordered,other,capacity(),area(),2000);
        assertEquals(a,b);
    }
}
