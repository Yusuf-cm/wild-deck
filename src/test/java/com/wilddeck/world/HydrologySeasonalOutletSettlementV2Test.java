package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalOutletSettlementV2Test {
    @TempDir Path directory;
    private static final GlobalHydrologyAddressV2.Sample A=at(2);
    private static final GlobalHydrologyAddressV2.Sample B=at(3);
    private static final GlobalHydrologyAddressV2.Sample C=at(4);
    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,2);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> edges() {
        var edges=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        edges.put(A,B); edges.put(B,C); edges.put(C,null);
        return edges;
    }
    private static HydrologyCheckpointV2.Checkpoint initial(
            Map<GlobalHydrologyAddressV2.Sample,Double> pending) {
        return HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "seeded-world",0,Map.of(),pending)),new HydrologyEpochInboxV2());
    }

    @Test void unloadedOceanMouthRemainsPendingThenSettlesOnceAfterRestart() throws IOException {
        var file=directory.resolve("outlet.chk");
        var first=initial(Map.of(A,7.0));
        HydrologyCheckpointStoreV2.save(file,first);
        var parked=HydrologySeasonalOutletSettlementV2.commitNext(
            file,first,edges(),Set.of(A,B),Set.of(),Set.of(C));
        assertEquals(Map.of(C,7.0),parked.state().lakes().pendingM3());
        assertTrue(parked.state().dischargedM3().isEmpty());
        assertEquals(first.inboxState(),parked.checkpoint().inboxState());
        var recovered=HydrologyCheckpointStoreV2.load(file);
        assertEquals(parked.state(),HydrologySeasonalOutletSettlementV2.decode(
            recovered.worldState()));
        var discharged=HydrologySeasonalOutletSettlementV2.commitNext(
            file,recovered,edges(),Set.of(C),Set.of(),Set.of(C));
        assertEquals(Map.of(C,7.0),discharged.state().dischargedM3());
        assertTrue(discharged.state().lakes().pendingM3().isEmpty());
        assertEquals(2,discharged.checkpoint().epoch());
        assertThrows(IOException.class,()->HydrologySeasonalOutletSettlementV2.commitNext(
            file,recovered,edges(),Set.of(C),Set.of(),Set.of(C)));
        var finalState=HydrologySeasonalOutletSettlementV2.decode(
            HydrologyCheckpointStoreV2.load(file).worldState());
        assertEquals(Map.of(C,7.0),finalState.dischargedM3());
        var quiet=HydrologySeasonalOutletSettlementV2.prepare(
            HydrologyCheckpointStoreV2.load(file),edges(),Set.of(C),Set.of(),Set.of(C));
        assertEquals(Map.of(C,7.0),quiet.state().dischargedM3());
        assertEquals(0.0,quiet.routing().outletTotalM3(),0.0);
    }

    @Test void confluenceIsConservedAndCumulativeLedgerSurvivesFurtherEpoch() {
        var first=initial(Map.of(A,2.5,B,3.75));
        var plan=HydrologySeasonalOutletSettlementV2.prepare(
            first,edges(),Set.of(A,B,C),Set.of(),Set.of(C));
        assertEquals(Map.of(C,6.25),plan.state().dischargedM3());
        assertEquals(6.25,plan.routing().inputM3(),1e-12);
        assertTrue(plan.state().lakes().pendingM3().isEmpty());
        var next=HydrologySeasonalOutletSettlementV2.prepare(
            plan.checkpoint(),edges(),Set.of(A,B,C),Set.of(),Set.of(C));
        assertEquals(Map.of(C,6.25),next.state().dischargedM3());
        assertEquals(0.0,next.routing().outletTotalM3(),0);
    }

    @Test void receiptInboxRemainsUnchangedByZeroForcingSettlement() {
        var inbox=new HydrologyEpochInboxV2();
        var key=new HydrologyTransferLedgerV2.Key(2,A,B);
        inbox.receive(key,3.0);
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(),Map.of(A,4.0))),inbox);
        var next=HydrologySeasonalOutletSettlementV2.prepare(
            checkpoint,edges(),Set.of(A,B,C),Set.of(),Set.of(C));
        assertEquals(checkpoint.inboxState(),next.checkpoint().inboxState());
        assertEquals(Map.of(C,4.0),next.state().dischargedM3());
        assertTrue(HydrologyCheckpointV2.restoreInbox(next.checkpoint())
            .snapshot().receipts().containsKey(key));
    }

    @Test void rejectsForgedMouthAndDoesNotWriteCheckpoint() throws IOException {
        var file=directory.resolve("forged.chk");
        var checkpoint=initial(Map.of(A,5.0));
        HydrologyCheckpointStoreV2.save(file,checkpoint);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalOutletSettlementV2.commitNext(
                file,checkpoint,edges(),Set.of(A,B,C),Set.of(),Set.of(B)));
        assertEquals(checkpoint,HydrologyCheckpointStoreV2.load(file));
    }

    @Test void rejectsAliasedCumulativeMouthsAndOverflow() {
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,4,2);
        var lake=HydrologySeasonalLakeCarryoverStateV2.decode(
            initial(Map.of()).worldState());
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySeasonalOutletSettlementV2.State(
                lake,Map.of(C,1.0,alias,2.0)));
        var saturated=new HydrologySeasonalOutletSettlementV2.State(
            new HydrologySeasonalLakeCarryoverStateV2.State(
                "world",0,Map.of(),Map.of(A,Double.MAX_VALUE)),
            Map.of(C,Double.MAX_VALUE));
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalOutletSettlementV2.encode(saturated),
            new HydrologyEpochInboxV2());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalOutletSettlementV2.prepare(
                expected,edges(),Set.of(A,B,C),Set.of(),Set.of(C)));
    }

    @Test void canonicalLedgerCodecRejectsTamperingAndRoundTrips() {
        var lake=HydrologySeasonalLakeCarryoverStateV2.decode(
            initial(Map.of(B,3.0)).worldState());
        var state=new HydrologySeasonalOutletSettlementV2.State(
            lake,Map.of(C,7.0));
        var encoded=HydrologySeasonalOutletSettlementV2.encode(state);
        assertEquals(state,HydrologySeasonalOutletSettlementV2.decode(encoded));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalOutletSettlementV2.decode(encoded+"\n"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalOutletSettlementV2.decode(
                encoded.replace("0x1.cp2","NaN")));
    }

    @Test void staleEpochFailsBeforeSettlement() {
        var lake=HydrologySeasonalLakeCarryoverStateV2.decode(
            initial(Map.of(A,1.0)).worldState());
        var bad=HydrologyCheckpointV2.capture(3,
            HydrologySeasonalLakeCarryoverStateV2.encode(lake),
            new HydrologyEpochInboxV2());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalOutletSettlementV2.prepare(
                bad,edges(),Set.of(A,B,C),Set.of(),Set.of(C)));
    }
    @Test void receivedBoundaryWaterAndOldPendingDischargeExactlyOnce() throws IOException {
        var file=directory.resolve("received-outlet.chk");
        var inbox=new HydrologyEpochInboxV2();
        var receipt=new HydrologyTransferLedgerV2.Key(0,A,B);
        assertTrue(inbox.receive(receipt,3.0));
        var old=new HydrologySeasonalLakeCarryoverStateV2.State(
            "world",0,Map.of(),Map.of(A,2.0));
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(old),inbox);
        HydrologyCheckpointStoreV2.save(file,expected);
        var result=HydrologySeasonalOutletSettlementV2.commitReceived(
            file,expected,edges(),Set.of(A,B,C),Set.of(),Set.of(C));
        assertEquals(Map.of(C,5.0),result.state().dischargedM3());
        assertTrue(result.state().lakes().pendingM3().isEmpty());
        var restored=HydrologyCheckpointStoreV2.load(file);
        assertEquals(result.state(),HydrologySeasonalOutletSettlementV2.decode(
            restored.worldState()));
        assertTrue(HydrologyCheckpointV2.restoreInbox(restored)
            .snapshot().delivered().contains(receipt));
        assertThrows(IOException.class,()->HydrologySeasonalOutletSettlementV2.commitReceived(
            file,expected,edges(),Set.of(A,B,C),Set.of(),Set.of(C)));
        assertEquals(Map.of(C,5.0),HydrologySeasonalOutletSettlementV2.decode(
            HydrologyCheckpointStoreV2.load(file).worldState()).dischargedM3());
    }

    @Test void unloadedBoundaryReceiptStaysUndeliveredUntilLaterEpoch() throws IOException {
        var file=directory.resolve("deferred-outlet.chk");
        var inbox=new HydrologyEpochInboxV2();
        var receipt=new HydrologyTransferLedgerV2.Key(0,A,B);
        inbox.receive(receipt,3.0);
        var old=new HydrologySeasonalLakeCarryoverStateV2.State(
            "world",0,Map.of(),Map.of());
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(old),inbox);
        HydrologyCheckpointStoreV2.save(file,expected);
        var parked=HydrologySeasonalOutletSettlementV2.commitReceived(
            file,expected,edges(),Set.of(A),Set.of(),Set.of(C));
        assertTrue(parked.state().dischargedM3().isEmpty());
        assertFalse(HydrologyCheckpointV2.restoreInbox(parked.checkpoint())
            .snapshot().delivered().contains(receipt));
        var next=HydrologySeasonalOutletSettlementV2.commitReceived(
            file,HydrologyCheckpointStoreV2.load(file),edges(),
            Set.of(B,C),Set.of(),Set.of(C));
        assertEquals(Map.of(C,3.0),next.state().dischargedM3());
        assertTrue(HydrologyCheckpointV2.restoreInbox(next.checkpoint())
            .snapshot().delivered().contains(receipt));
    }

    @Test void staleReceiptAppendCannotBeLostDuringOutletCommit() throws IOException {
        var file=directory.resolve("receipt-race.chk");
        var expected=initial(Map.of(A,2.0));
        HydrologyCheckpointStoreV2.save(file,expected);
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        inbox.receive(new HydrologyTransferLedgerV2.Key(2,A,B),4.0);
        var appended=HydrologyCheckpointV2.capture(0,expected.worldState(),inbox);
        HydrologyCheckpointStoreV2.saveIncoming(file,appended);
        assertThrows(IOException.class,()->HydrologySeasonalOutletSettlementV2.commitReceived(
            file,expected,edges(),Set.of(A,B,C),Set.of(),Set.of(C)));
        assertEquals(appended,HydrologyCheckpointStoreV2.load(file));
    }

}
