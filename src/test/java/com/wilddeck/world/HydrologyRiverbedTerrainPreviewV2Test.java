package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRiverbedTerrainPreviewV2Test {
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
        return at -> at.equals(A)?12:at.equals(B)?10:5;
    }
    private static HydrologyTerrainOverlayV2.State previous() {
        return new HydrologyTerrainOverlayV2.State("season-zero",Map.of(at(7,3),42.0));
    }

    @Test void conservativeDepositionPreservesExistingTerrainAndDownhillEdges() {
        var before=previous();
        var preview=HydrologyRiverbedTerrainPreviewV2.plan(
            drainage(),before,seed(),Map.of(B,1000.0),
            Map.of(B,1.0),1000,1.5);
        assertEquals(1000,preview.depositedMass(),0.0);
        assertEquals(1,preview.changedVertices());
        assertEquals(11.0,preview.terrain().elevations().get(B),0.0);
        assertEquals(42.0,preview.terrain().elevations().get(at(7,3)),0.0);
        assertFalse(before.elevations().containsKey(B));
        assertEquals("season-zero",preview.terrain().world());
    }

    @Test void depositionReversingAnUpstreamRiverIsRejected() {
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedTerrainPreviewV2.plan(
            drainage(),previous(),seed(),Map.of(B,2000.0),
            Map.of(B,1.0),1000,3));
    }

    @Test void rejectsUnloadedFrontiersAndTerminalDeposits() {
        var unloaded=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(B,0,C,false)),List.of());
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedTerrainPreviewV2.plan(
            unloaded,previous(),seed(),Map.of(B,100.0),
            Map.of(B,1.0),1000,1));
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedTerrainPreviewV2.plan(
            drainage(),previous(),seed(),Map.of(C,100.0),
            Map.of(C,1.0),1000,1));
    }

    @Test void refusesToApplyRiverDepositsInsideLakeCells() {
        var lake=new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,C,
            new LakeReservoirV2.Basin(new double[]{10},new double[]{1},12));
        var plan=new HydrologySphericalLakeBasinsV2.Plan(drainage().routes(),List.of(lake));
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedTerrainPreviewV2.plan(
            plan,previous(),seed(),Map.of(B,100.0),
            Map.of(B,1.0),1000,1));
    }

    @Test void rejectsSharedPhysicalVerticesAcrossSamplingResolutions() {
        var alias=new GlobalHydrologyAddressV2.Sample(2,4,4,8);
        var duplicated=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,null,true),
            new HydrologyFlowGraphV2.Node(alias,0,null,true)),List.of());
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedTerrainPreviewV2.plan(
            duplicated,previous(),seed(),Map.of(),Map.of(),1000,1));
    }

    @Test void invalidAreasAndRiseBudgetsFailWithoutChangingInput() {
        var before=previous();
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedTerrainPreviewV2.plan(
            drainage(),before,seed(),Map.of(B,100.0),Map.of(B,0.0),1000,1));
        assertThrows(IllegalArgumentException.class,()->HydrologyRiverbedTerrainPreviewV2.plan(
            drainage(),before,seed(),Map.of(B,1000.0),Map.of(B,1.0),1000,0.5));
        assertEquals(Map.of(at(7,3),42.0),before.elevations());
    }

    @Test void mapOrderCannotChangeDeterministicPreview() {
        var left=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        left.put(A,100.0);left.put(B,100.0);
        var right=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        right.put(B,100.0);right.put(A,100.0);
        var areas=Map.of(A,1.0,B,1.0);
        var one=HydrologyRiverbedTerrainPreviewV2.plan(
            drainage(),previous(),seed(),left,areas,1000,1);
        var two=HydrologyRiverbedTerrainPreviewV2.plan(
            drainage(),previous(),seed(),right,areas,1000,1);
        assertEquals(HydrologyTerrainOverlayV2.encode(one.terrain()),
                     HydrologyTerrainOverlayV2.encode(two.terrain()));
    }
}
