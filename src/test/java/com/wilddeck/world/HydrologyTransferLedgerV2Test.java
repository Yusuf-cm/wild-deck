package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTransferLedgerV2Test {
    private static GlobalHydrologyAddressV2.Sample at(int x,int y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    @Test void duplicateDeliveryIsIgnoredAndConfluencesAreAddedOnce() {
        var a=new HydrologyTransferLedgerV2.Key(7,at(1,2),at(2,2));
        var b=new HydrologyTransferLedgerV2.Key(7,at(2,1),at(2,2));
        var ledger=new HydrologyTransferLedgerV2();
        assertTrue(ledger.accept(a,4));
        assertFalse(ledger.accept(a,4));
        assertTrue(ledger.accept(b,6));
        assertEquals(10,ledger.incoming(7).get(at(2,2)));
        assertThrows(IllegalArgumentException.class,()->ledger.accept(a,5));
        assertEquals(10,ledger.incoming(7).get(at(2,2)));
    }
    @Test void epochsAreIsolatedAndSnapshotRestoresExactly() {
        var edge=new HydrologyTransferLedgerV2.Key(1,at(1,2),at(2,2));
        var later=new HydrologyTransferLedgerV2.Key(2,at(1,2),at(2,2));
        var ledger=new HydrologyTransferLedgerV2();
        ledger.accept(edge,4);ledger.accept(later,9);
        var restored=HydrologyTransferLedgerV2.restore(ledger.snapshot());
        assertEquals(4,restored.incoming(1).get(at(2,2)));
        assertEquals(9,restored.incoming(2).get(at(2,2)));
        assertEquals(ledger.snapshot(),restored.snapshot());
        assertFalse(restored.accept(later,9));
    }
    @Test void invalidRoutesAndNegativeFlowAreRejected() {
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTransferLedgerV2.Key(0,at(1,2),at(5,2)));
        var key=new HydrologyTransferLedgerV2.Key(0,at(1,2),at(2,2));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTransferLedgerV2().accept(key,-1));
    }

    @Test void legacyEpochAggregationRejectsLostPositiveConfluence(){
        var ledger=new HydrologyTransferLedgerV2();
        var mouth=at(2,2);
        ledger.accept(new HydrologyTransferLedgerV2.Key(5,at(1,2),mouth),1e20);
        ledger.accept(new HydrologyTransferLedgerV2.Key(5,at(2,1),mouth),1);
        assertThrows(IllegalArgumentException.class,()->ledger.incoming(5));
        assertEquals(2,ledger.snapshot().size());
    }

    @Test void rejectsTwoDischargesFromOnePhysicalSourceInSameEpoch() {
        var ledger=new HydrologyTransferLedgerV2();
        var first=new HydrologyTransferLedgerV2.Key(7,at(4,4),at(5,4));
        var fork=new HydrologyTransferLedgerV2.Key(7,at(4,4),at(4,5));
        assertTrue(ledger.accept(first,12));
        assertThrows(IllegalArgumentException.class,()->ledger.accept(fork,8));
        assertEquals(1,ledger.snapshot().size());
        assertEquals(12,ledger.incoming(7).get(at(5,4)));
        assertTrue(ledger.accept(new HydrologyTransferLedgerV2.Key(
            8,at(4,4),at(4,5)),8));
    }

    @Test void rejectsCrossResolutionPhysicalSenderAliasAndForgedRestore() {
        var fine=new HydrologyTransferLedgerV2.Key(7,at(4,4),at(5,4));
        var coarse=new HydrologyTransferLedgerV2.Key(7,
            new GlobalHydrologyAddressV2.Sample(1,4,2,2),
            new GlobalHydrologyAddressV2.Sample(1,4,3,2));
        var ledger=new HydrologyTransferLedgerV2();
        ledger.accept(fine,12);
        assertThrows(IllegalArgumentException.class,()->ledger.accept(coarse,8));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTransferLedgerV2.restore(java.util.Map.of(fine,12.0,coarse,8.0)));
    }
}
