package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Receiving sediment is a separate, explicit contribution to a river epoch. */
class HydrologyRiverSedimentIncomingV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(0,2),
        B=at(1,2), C=at(2,2), D=at(1,3), OUTSIDE=at(3,2);

    private static HydrologySphericalLakeBasinsV2.Plan plan() {
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(D,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> local() {
        return Map.of(A,2.0,D,3.0,B,0.0,C,0.0);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> capacity() {
        return Map.of(A,100.0,D,100.0,B,100.0,C,100.0);
    }

    @Test void importsConvergeWithLocalSedimentWithoutDuplicateSource() {
        var result=HydrologyRiverSedimentTransportV2.routeWithIncoming(
            plan(),local(),capacity(),Map.of(B,7.0,C,11.0));
        assertEquals(5,result.localEroded(),1e-12);
        assertEquals(18,result.imported(),1e-12);
        assertEquals(23,result.result().budget().eroded(),1e-12);
        assertEquals(23,result.result().terminalExport().get(C),1e-12);
        assertEquals(0,result.result().budget().residual(),1e-12);
    }

    @Test void capacityDepositsImportedMassAndPreservesBudget() {
        var cap=new HashMap<>(capacity());
        cap.put(B,4.0);
        var result=HydrologyRiverSedimentTransportV2.routeWithIncoming(
            plan(),local(),cap,Map.of(B,7.0));
        assertEquals(8,result.result().riverDeposited().get(B),1e-12);
        assertEquals(4,result.result().terminalExport().get(C),1e-12);
        assertEquals(12,result.result().budget().eroded(),1e-12);
        assertEquals(0,result.result().budget().residual(),1e-12);
    }

    @Test void unloadedDestinationRetainsImportedMassAsPending() {
        var partial=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false)),List.of());
        var result=HydrologyRiverSedimentTransportV2.routeWithIncoming(
            partial,Map.of(A,2.0),Map.of(A,100.0),Map.of(A,5.0));
        assertEquals(7,result.result().pendingExport().get(B),1e-12);
        assertEquals(7,result.result().budget().pendingExported(),1e-12);
        assertEquals(0,result.result().budget().residual(),1e-12);
    }

    @Test void unknownInvalidOrNullImportsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.routeWithIncoming(
                plan(),local(),capacity(),Map.of(OUTSIDE,1.0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.routeWithIncoming(
                plan(),local(),capacity(),Map.of(B,Double.NaN)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverSedimentTransportV2.routeWithIncoming(
                plan(),local(),capacity(),Map.of(B,-1.0)));
        assertThrows(NullPointerException.class,()->
            HydrologyRiverSedimentTransportV2.routeWithIncoming(
                plan(),local(),capacity(),null));
    }

    @Test void sourceEnumerationAndMapOrderingDoNotAffectResults() {
        var routes=new ArrayList<>(plan().routes());
        Collections.reverse(routes);
        var reordered=new HydrologySphericalLakeBasinsV2.Plan(routes,List.of());
        var imports=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        imports.put(C,11.0);
        imports.put(B,7.0);
        var first=HydrologyRiverSedimentTransportV2.routeWithIncoming(
            plan(),local(),capacity(),Map.of(B,7.0,C,11.0));
        var second=HydrologyRiverSedimentTransportV2.routeWithIncoming(
            reordered,local(),capacity(),imports);
        assertEquals(first,second);
    }

    @Test void legacyRouteKeepsLocalOnlyAccounting() {
        var result=HydrologyRiverSedimentTransportV2.route(
            plan(),local(),capacity());
        assertEquals(5,result.budget().eroded(),1e-12);
        assertEquals(5,result.terminalExport().get(C),1e-12);
    }
}
