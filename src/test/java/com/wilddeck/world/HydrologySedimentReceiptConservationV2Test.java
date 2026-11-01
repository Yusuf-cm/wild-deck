package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentReceiptConservationV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(0,2),
        B=at(1,2), C=at(2,2), D=at(1,3), E=at(0,3);

    @Test void validSameSourceDifferentDestinationIsRejected() {
        var first=new HydrologySedimentReceiptInboxV2.Receipt(1,2,A,B);
        var fork=new HydrologySedimentReceiptInboxV2.Receipt(1,3,A,E);
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),first,2);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptInboxV2.receive(inbox,fork,2));
    }

    @Test void seededConfluencesPreserveMassThroughUnloadedRestart() {
        var random=new Random(7262);
        for (int i=0;i<200;i++) {
            double a=random.nextInt(100_000)/100.0;
            double d=random.nextInt(100_000)/100.0;
            var first=new HydrologySedimentReceiptInboxV2.Receipt(i,i+1,A,B);
            var second=new HydrologySedimentReceiptInboxV2.Receipt(i,i+1,D,B);
            var snapshot=HydrologySedimentReceiptInboxV2.receive(
                HydrologySedimentReceiptInboxV2.empty(),first,a);
            snapshot=HydrologySedimentReceiptInboxV2.receive(snapshot,second,d);
            // The unloaded destination stays parked; restore the immutable
            // snapshot before the later receiving epoch to simulate restart.
            var parked=HydrologySedimentReceiptInboxV2.claimAvailable(
                snapshot,i+1,List.of(C));
            assertTrue(parked.imported().isEmpty());
            var restored=new HydrologySedimentReceiptInboxV2.Snapshot(
                parked.snapshot().receipts(),parked.snapshot().delivered(),
                parked.snapshot().lastClaimedEpoch());
            var delivered=HydrologySedimentReceiptInboxV2.claimAvailable(
                restored,i+2,List.of(B));
            assertEquals(a+d,delivered.imported().get(B),1e-9);
            assertEquals(a+d,delivered.importedMass(),1e-9);
            assertEquals(2,delivered.snapshot().delivered().size());
            assertTrue(HydrologySedimentReceiptInboxV2.claimAvailable(
                delivered.snapshot(),i+3,List.of(B)).imported().isEmpty());
        }
    }
}
