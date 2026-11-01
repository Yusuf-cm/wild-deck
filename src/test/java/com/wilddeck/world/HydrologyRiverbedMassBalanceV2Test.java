package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverbedMassBalanceV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(1,2), B=at(2,2), C=at(3,2);

    private static HydrologySphericalLakeBasinsV2.Plan drainage() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return at -> at.equals(A)?15:at.equals(B)?10:5;
    }
    private static HydrologyTerrainOverlayV2.State terrain() {
        return new HydrologyTerrainOverlayV2.State("before",Map.of(at(7,3),42.0));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> eroded() {
        return Map.of(A,1000.0,B,0.0,C,0.0);
    }
    private static HydrologyRiverSedimentTransportV2.Result transported() {
        return HydrologyRiverSedimentTransportV2.route(drainage(),eroded(),
            Map.of(A,1000.0,B,0.0,C,0.0));
    }
    private static HydrologyRiverbedMassBalanceV2.Preview preview(
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Double> cut,
            HydrologyRiverSedimentTransportV2.Result routed,
            Map<GlobalHydrologyAddressV2.Sample,Double> area) {
        return HydrologyRiverbedMassBalanceV2.plan(plan,terrain(),seed(),
            cut,routed,area,1000,2,2);
    }

    @Test void incisionAndDepositionConserveMassAndPreserveUnrelatedTerrain() {
        var before=terrain();
        var result=preview(drainage(),eroded(),transported(),Map.of(A,1.0,B,1.0));
        assertEquals(1000.0,result.erodedMass(),0.0);
        assertEquals(1000.0,result.depositedMass(),0.0);
        assertEquals(2,result.changedVertices());
        assertEquals(14.0,result.terrain().elevations().get(A),0.0);
        assertEquals(11.0,result.terrain().elevations().get(B),0.0);
        assertEquals(42.0,result.terrain().elevations().get(at(7,3)),0.0);
        assertFalse(before.elevations().containsKey(A));
        assertEquals("before",result.terrain().world());
    }

    @Test void deterministicAcrossMapIterationOrder() {
        var left=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        left.put(A,1000.0);left.put(B,0.0);left.put(C,0.0);
        var right=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        right.put(C,0.0);right.put(B,0.0);right.put(A,1000.0);
        var first=preview(drainage(),left,transported(),Map.of(A,1.0,B,1.0));
        var second=preview(drainage(),right,transported(),Map.of(B,1.0,A,1.0));
        assertEquals(HydrologyTerrainOverlayV2.encode(first.terrain()),
                     HydrologyTerrainOverlayV2.encode(second.terrain()));
    }

    @Test void uphillEditFailsBeforeAnyTerrainMutation() {
        var before=terrain();
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedMassBalanceV2.plan(
            drainage(),before,seed(),eroded(),transported(),
            Map.of(A,1.0,B,0.1),1000,2,20));
        assertEquals(Map.of(at(7,3),42.0),before.elevations());
    }

    @Test void pendingSedimentExportCannotBeHiddenAsLocalBedChange() {
        var export=HydrologyRiverSedimentTransportV2.route(drainage(),eroded(),
            Map.of(A,1000.0,B,1000.0,C,1000.0));
        assertTrue(export.budget().terminalExported()>0);
        assertThrows(IllegalArgumentException.class,()->preview(
            drainage(),eroded(),export,Map.of(A,1.0,B,1.0)));
    }

    @Test void incorrectErosionSourceBudgetAndAreaAreRejected() {
        assertThrows(IllegalArgumentException.class,()->preview(
            drainage(),Map.of(A,500.0,B,0.0,C,0.0),transported(),
            Map.of(A,1.0,B,1.0)));
        assertThrows(IllegalArgumentException.class,()->preview(
            drainage(),eroded(),transported(),Map.of(A,0.0,B,1.0)));
        assertThrows(IllegalArgumentException.class,()->preview(
            drainage(),eroded(),transported(),Map.of(A,1.0,B,Double.NaN)));
    }

    @Test void sharedPhysicalVertexAndLakeCellEditsFailClosed() {
        var alias=new GlobalHydrologyAddressV2.Sample(2,4,4,4);
        var duplicate=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true),
            new HydrologyFlowGraphV2.Node(alias,0,null,true)),List.of());
        assertThrows(IllegalArgumentException.class,()->preview(
            duplicate,eroded(),transported(),Map.of(A,1.0,B,1.0)));
        var lake=new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,C,
            new LakeReservoirV2.Basin(new double[]{10},new double[]{1},12));
        var withLake=new HydrologySphericalLakeBasinsV2.Plan(
            drainage().routes(),List.of(lake));
        assertThrows(IllegalArgumentException.class,()->preview(
            withLake,eroded(),transported(),Map.of(A,1.0,B,1.0)));
    }
}
