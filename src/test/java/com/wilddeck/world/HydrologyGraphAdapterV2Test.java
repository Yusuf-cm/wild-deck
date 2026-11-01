package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyGraphAdapterV2Test {
    private static RegionalHydrologyV2.Hydrology syntheticMosaic() {
        int width=3,height=3;
        double[] terrain=new double[9],rain=new double[9];
        Arrays.fill(terrain,10);
        Arrays.fill(rain,1);
        var drainage=RegionalDrainageV2.route(width,height,terrain,rain);
        var mosaic=new ConnectedDrainageV2.Mosaic(width,height,drainage,terrain,rain);
        var lakes=LakeSpillV2.fill(width,height,terrain);
        var discharge=SpillDischargeV2.accumulate(lakes,rain);
        return new RegionalHydrologyV2.Hydrology(
            mosaic,lakes,discharge,RiverNetworkV2.extract(lakes,discharge,1));
    }

    private static double sum(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    @Test void finiteMosaicConservesWaterWithoutInventingRiverMouths() {
        var graph=HydrologyFlowGraphV2.solve(
            HydrologyGraphAdapterV2.nodes(syntheticMosaic(),2,2,0,0));
        assertTrue(graph.terminalOutflow().isEmpty());
        assertEquals(9,sum(graph.pendingOutflow().values()),1e-9);
        assertTrue(graph.resolvedCatchments().isEmpty());
    }

    @Test void explicitNeighborLinkCanResumeThroughUnloadedFrontier() {
        var from=new GlobalHydrologyAddressV2.Sample(2,2,2,1);
        var to=new GlobalHydrologyAddressV2.Sample(2,2,3,1);
        var nodes=HydrologyGraphAdapterV2.nodes(
            syntheticMosaic(),2,2,0,0,Map.of(from,to));
        var partial=HydrologyFlowGraphV2.solve(nodes);
        assertEquals(1,partial.pendingOutflow().get(to));
        var complete=new ArrayList<>(nodes);
        complete.add(new HydrologyFlowGraphV2.Node(to,0,null,true));
        var result=HydrologyFlowGraphV2.solve(complete);
        assertEquals(1,result.terminalOutflow().get(to));
        assertEquals(to,result.resolvedCatchments().get(from));
        assertEquals(9,sum(result.terminalOutflow().values())+
                       sum(result.pendingOutflow().values()),1e-9);
    }

    @Test void rejectsExternalRouteThatTeleportsOrOverridesInteriorSpill() {
        var from=new GlobalHydrologyAddressV2.Sample(2,2,2,1);
        var far=new GlobalHydrologyAddressV2.Sample(2,2,5,1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyGraphAdapterV2.nodes(syntheticMosaic(),2,2,0,0,Map.of(from,far)));
        var center=new GlobalHydrologyAddressV2.Sample(2,2,1,1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyGraphAdapterV2.nodes(syntheticMosaic(),2,2,0,0,Map.of(center,from)));
    }

    @Test void seededConnectedHydrologyRoutesToKnownOrPendingOutlets() {
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(world);
        var hydrology=RegionalHydrologyV2.generate(world,climate,2,1,1,2,2,4,1);
        var nodes=HydrologyGraphAdapterV2.nodes(hydrology,2,4,1,1);
        var result=HydrologyFlowGraphV2.solve(nodes);
        double rain=Arrays.stream(hydrology.terrain().rainfall()).sum();
        assertEquals(rain,sum(result.terminalOutflow().values())+
                          sum(result.pendingOutflow().values()),1e-6);
    }
}
