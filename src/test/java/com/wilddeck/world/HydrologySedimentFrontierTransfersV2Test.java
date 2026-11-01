package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentFrontierTransfersV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(0,2),
        B=at(1,2), D=at(1,3), OUT=at(2,2);
    private static HydrologySphericalLakeBasinsV2.Plan plan() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(D,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,OUT,false)),List.of());
    }

    @Test void frontierPreservesSenderEdgeAndMatchesDestinationAggregate() {
        var routed=HydrologyRiverSedimentTransportV2.route(plan(),
            Map.of(A,2.0,D,3.0,B,1.0),Map.of(A,100.0,D,100.0,B,100.0));
        var outgoing=HydrologySedimentFrontierTransfersV2.prepare(plan(),routed,4,5);
        assertEquals(1,outgoing.receipts().size());
        var key=new HydrologySedimentReceiptInboxV2.Receipt(4,5,B,OUT);
        assertEquals(6,outgoing.receipts().get(key),1e-12);
        assertEquals(6,outgoing.pendingMass(),1e-12);
        var accepted=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),key,6);
        assertEquals(6,HydrologySedimentReceiptInboxV2.claimAvailable(
            accepted,5,List.of(OUT)).importedMass(),1e-12);
    }

    @Test void twoBoundarySendersConvergeWithoutCollapsingProvenance() {
        var graph=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(D,0,B,false)),List.of());
        var routed=HydrologyRiverSedimentTransportV2.route(graph,
            Map.of(A,2.0,D,3.0),Map.of(A,100.0,D,100.0));
        var outgoing=HydrologySedimentFrontierTransfersV2.prepare(graph,routed,1,2);
        assertEquals(2,outgoing.receipts().size());
        assertEquals(5,outgoing.pendingMass(),1e-12);
        var inbox=HydrologySedimentReceiptInboxV2.empty();
        for (var entry:outgoing.receipts().entrySet())
            inbox=HydrologySedimentReceiptInboxV2.receive(inbox,entry.getKey(),entry.getValue());
        assertEquals(5,HydrologySedimentReceiptInboxV2.claimAvailable(
            inbox,2,List.of(B)).imported().get(B),1e-12);
    }

    @Test void unknownFrontierWithoutReceivingEdgeFailsClosed() {
        var graph=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,null,false)),List.of());
        var routed=HydrologyRiverSedimentTransportV2.route(graph,
            Map.of(A,2.0),Map.of(A,100.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentFrontierTransfersV2.prepare(graph,routed,1,2));
    }

    @Test void forgedPendingMassAndEpochAreRejected() {
        var routed=HydrologyRiverSedimentTransportV2.route(plan(),
            Map.of(A,2.0,D,3.0,B,1.0),Map.of(A,100.0,D,100.0,B,100.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentFrontierTransfersV2.prepare(plan(),routed,3,2));
        var forged=new HydrologyRiverSedimentTransportV2.Result(
            routed.riverDeposited(),routed.lakeCaptured(),routed.transported(),
            routed.terminalExport(),Map.of(OUT,999.0),routed.budget());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentFrontierTransfersV2.prepare(plan(),forged,1,2));
    }
}
