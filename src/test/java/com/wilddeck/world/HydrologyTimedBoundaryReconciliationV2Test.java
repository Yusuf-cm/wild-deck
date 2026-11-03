package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedBoundaryReconciliationV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static HydrologySphericalRouteAtlasV2 atlas(
            GlobalHydrologyAddressV2.Sample outlet) {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for (long y=1;y<4;y++) for (long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(outlet)?0:10,Set.of(outlet));
    }
    private static GlobalHydrologyAddressV2.Sample upstream(
            HydrologySphericalRouteAtlasV2 atlas,
            GlobalHydrologyAddressV2.Sample destination) {
        return atlas.routes().stream().filter(r->destination.equals(r.downstream()))
            .findFirst().orElseThrow().address();
    }
    private static HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge(
            String id,GlobalHydrologyAddressV2.Sample source,
            GlobalHydrologyAddressV2.Sample dest,double amount) {
        return new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
            id,source,dest,3,amount);
    }
    private static HydrologyTimedTransitLedgerV2.Transfer receipt(
            HydrologyVerifiedTimedBoundaryV2.EdgeTransfer edge) {
        return new HydrologyTimedTransitLedgerV2.Transfer(
            edge.id(),HydrologyGlobalCatchmentsV2.coordinate(edge.destination()),
            edge.dueTick(),edge.volumeM3());
    }
    private static void same(String value,BigDecimal actual) {
        assertEquals(0,new BigDecimal(value).compareTo(actual));
    }

    @Test void reconcilesMissingPendingAndSettledWithoutLosingOrMintingWater() {
        var dest=at(5,2); var routes=atlas(dest); var source=upstream(routes,dest);
        var a=edge("missing",source,dest,2);
        var b=edge("pending",source,dest,3);
        var c=edge("settled",source,dest,5);
        var sender=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(source,20.0)),
            routes,List.of(c,a,b)).state();
        var receiver=new HydrologyTimedTransitLedgerV2.State("world",5,
            Map.of(b.id(),receipt(b)),Map.of(c.id(),receipt(c)));
        var result=HydrologyTimedBoundaryReconciliationV2.audit(sender,receiver,routes);
        assertEquals(List.of(a),result.missing());
        same("10.0",result.senderAvailableM3());
        same("10.0",result.senderIssuedM3());
        same("3.0",result.receiverPendingM3());
        same("5.0",result.receiverSettledM3());
        same("2.0",result.unreceivedM3());
        same("20.0",result.accountedM3());
        var replay=HydrologyTimedTransitLedgerV2.advance(receiver,
            List.of(receipt(a)),5,Set.of());
        var recovered=HydrologyTimedBoundaryReconciliationV2.audit(
            sender,replay.state(),routes);
        assertTrue(recovered.missing().isEmpty());
        same("20.0",recovered.accountedM3());
        same("5.0",recovered.receiverPendingM3());
    }

    @Test void unrelatedReceiverTransferIsNotAttributedToThisSender() {
        var dest=at(5,2); var routes=atlas(dest); var source=upstream(routes,dest);
        var a=edge("owned",source,dest,2);
        var sender=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(source,7.0)),
            routes,List.of(a)).state();
        var foreign=new HydrologyTimedTransitLedgerV2.Transfer(
            "foreign",HydrologyGlobalCatchmentsV2.coordinate(dest),1,100);
        var receiver=new HydrologyTimedTransitLedgerV2.State(
            "world",4,Map.of("foreign",foreign),Map.of("owned",receipt(a)));
        var audit=HydrologyTimedBoundaryReconciliationV2.audit(sender,receiver,routes);
        same("7.0",audit.accountedM3());
        same("2.0",audit.receiverSettledM3());
        assertTrue(audit.missing().isEmpty());
    }

    @Test void conflictingReceiptAndForeignWorldFailClosed() {
        var dest=at(5,2); var routes=atlas(dest); var source=upstream(routes,dest);
        var a=edge("owned",source,dest,2);
        var sender=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(source,7.0)),
            routes,List.of(a)).state();
        var forged=new HydrologyTimedTransitLedgerV2.Transfer(
            "owned",HydrologyGlobalCatchmentsV2.coordinate(dest),3,4);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryReconciliationV2.audit(sender,
                new HydrologyTimedTransitLedgerV2.State(
                    "world",3,Map.of("owned",forged),Map.of()),routes));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryReconciliationV2.audit(sender,
                HydrologyTimedTransitLedgerV2.empty("different"),routes));
    }

    @Test void changedDrainageRejectsExistingSenderJournal() {
        var dest=at(5,2); var routes=atlas(dest); var source=upstream(routes,dest);
        var sender=HydrologyTimedBoundarySenderV2.advance(
            HydrologyTimedBoundarySenderV2.empty("world",Map.of(source,7.0)),
            routes,List.of(edge("owned",source,dest,2))).state();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedBoundaryReconciliationV2.audit(sender,
                HydrologyTimedTransitLedgerV2.empty("world"),atlas(at(0,2))));
    }
}
