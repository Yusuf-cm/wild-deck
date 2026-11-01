package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentReceiptInboxV2Test {
    private static GlobalHydrologyAddressV2.Sample at(int level,int divisions,long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(level,divisions,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(1,2,0,2),
        B=at(1,2,1,2), C=at(1,2,2,2), D=at(1,2,1,3),
        FAR=at(1,2,3,2);

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(long source,long arrival,
            GlobalHydrologyAddressV2.Sample from,GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(source,arrival,from,to);
    }

    @Test void convergingSourcesAreClaimedExactlyOnce() {
        var first=receipt(0,2,A,B);
        var second=receipt(0,2,D,B);
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),first,7);
        inbox=HydrologySedimentReceiptInboxV2.receive(inbox,second,11);
        var claimed=HydrologySedimentReceiptInboxV2.claimAvailable(inbox,2,List.of(B));
        assertEquals(18,claimed.imported().get(B),1e-12);
        assertEquals(18,claimed.importedMass(),1e-12);
        assertEquals(2,claimed.snapshot().delivered().size());
        assertSame(claimed.snapshot(),HydrologySedimentReceiptInboxV2.receive(
            claimed.snapshot(),first,7));
        var next=HydrologySedimentReceiptInboxV2.claimAvailable(
            claimed.snapshot(),3,List.of(B));
        assertTrue(next.imported().isEmpty());
        assertThrows(IllegalStateException.class,()->
            HydrologySedimentReceiptInboxV2.claimAvailable(claimed.snapshot(),2,List.of(B)));
    }

    @Test void unloadedDestinationWaitsForLaterEpochAndFutureArrivalsWait() {
        var first=receipt(0,1,A,B);
        var later=receipt(0,4,D,C);
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),first,5);
        inbox=HydrologySedimentReceiptInboxV2.receive(inbox,later,9);
        var epoch1=HydrologySedimentReceiptInboxV2.claimAvailable(inbox,1,List.of(C));
        assertTrue(epoch1.imported().isEmpty());
        var epoch2=HydrologySedimentReceiptInboxV2.claimAvailable(epoch1.snapshot(),2,List.of(B,C));
        assertEquals(5,epoch2.imported().get(B),1e-12);
        assertFalse(epoch2.imported().containsKey(C));
        var epoch4=HydrologySedimentReceiptInboxV2.claimAvailable(epoch2.snapshot(),4,List.of(C));
        assertEquals(9,epoch4.imported().get(C),1e-12);
        assertEquals(2,epoch4.snapshot().delivered().size());
    }

    @Test void rejectsSamePhysicalSenderForkAndCrossResolutionAlias() {
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),receipt(0,2,A,B),3);
        var samePhysical=at(2,2,0,4);
        var aliasTarget=at(2,2,1,4);
        var saved=inbox;
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptInboxV2.receive(saved,
                receipt(0,2,samePhysical,aliasTarget),3));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptInboxV2.receive(saved,receipt(0,3,A,C),3));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptInboxV2.receive(saved,receipt(0,2,A,B),4));
        var next=HydrologySedimentReceiptInboxV2.receive(saved,receipt(1,3,A,B),3);
        assertEquals(2,next.receipts().size());
    }

    @Test void rejectsDuplicateLoadedPhysicalOwnershipAcrossResolutions() {
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),receipt(0,2,A,B),2);
        var alias=at(2,2,2,4);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptInboxV2.claimAvailable(inbox,2,List.of(B,alias)));
        assertEquals(-1,inbox.lastClaimedEpoch());
    }

    @Test void routesToLoadedOwnerAtDifferentResolution() {
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),receipt(0,1,A,B),6);
        var alias=at(2,2,2,4);
        var claimed=HydrologySedimentReceiptInboxV2.claimAvailable(inbox,1,List.of(alias));
        assertEquals(6,claimed.imported().get(alias),1e-12);
        assertFalse(claimed.imported().containsKey(B));
    }

    @Test void invalidEpochEdgeMassAndLateNewArrivalFailClosed() {
        assertThrows(IllegalArgumentException.class,()->receipt(3,2,A,B));
        assertThrows(IllegalArgumentException.class,()->receipt(0,1,A,FAR));
        assertThrows(IllegalArgumentException.class,()->receipt(0,1,A,A));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptInboxV2.receive(
                HydrologySedimentReceiptInboxV2.empty(),receipt(0,1,A,B),Double.NaN));
        var closed=HydrologySedimentReceiptInboxV2.claimAvailable(
            HydrologySedimentReceiptInboxV2.empty(),1,List.of(B));
        assertThrows(IllegalStateException.class,()->
            HydrologySedimentReceiptInboxV2.receive(
                closed.snapshot(),receipt(0,1,A,B),1));
    }

    @Test void forgedSnapshotAndOverflowDoNotConsumeEpoch() {
        var key=receipt(0,1,A,B);
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySedimentReceiptInboxV2.Snapshot(Map.of(),Set.of(key),1));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologySedimentReceiptInboxV2.Snapshot(Map.of(key,1.0),Set.of(key),0));
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),key,Double.MAX_VALUE);
        inbox=HydrologySedimentReceiptInboxV2.receive(inbox,receipt(0,1,D,B),Double.MAX_VALUE);
        var saved=inbox;
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptInboxV2.claimAvailable(saved,1,List.of(B)));
        assertTrue(saved.delivered().isEmpty());
        assertEquals(-1,saved.lastClaimedEpoch());
    }

    @Test void insertionOrderDoesNotChangeSnapshotOrClaims() {
        var a=receipt(0,2,A,B);
        var d=receipt(0,2,D,B);
        var left=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.receive(
                HydrologySedimentReceiptInboxV2.empty(),a,2),d,3);
        var right=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.receive(
                HydrologySedimentReceiptInboxV2.empty(),d,3),a,2);
        assertEquals(left,right);
        assertEquals(HydrologySedimentReceiptInboxV2.claimAvailable(left,2,List.of(B)),
                     HydrologySedimentReceiptInboxV2.claimAvailable(right,2,List.of(B)));
    }

    @Test void stagedRiverRoutingPreservesCallerSnapshotOnFailure() {
        var key=receipt(0,1,A,B);
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),key,5);
        var plan=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
        var staged=HydrologySedimentReceiptRoutingV2.stageRiver(
            inbox,1,plan,Map.of(B,2.0,C,0.0),Map.of(B,100.0,C,100.0));
        assertEquals(5,staged.result().imported(),1e-12);
        assertEquals(7,staged.result().result().terminalExport().get(C),1e-12);
        assertEquals(-1,inbox.lastClaimedEpoch());
        assertEquals(1,staged.snapshot().lastClaimedEpoch());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptRoutingV2.stageRiver(inbox,1,plan,
                Map.of(B,2.0),Map.of(B,100.0,C,100.0)));
        assertTrue(inbox.delivered().isEmpty());
    }

    @Test void stagedLakeFailureCannotAcknowledgeSediment() {
        var inbox=HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),receipt(0,1,A,B),5);
        var plan=new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,null,true)),List.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentReceiptRoutingV2.stageLake(inbox,1,plan,
                new HydrologyLakeStateV2.State("world",Map.of()),Map.of(),
                Map.of(B,0.0,C,0.0),Map.of(B,100.0,C,100.0),
                -1.0,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP));
        assertEquals(-1,inbox.lastClaimedEpoch());
        assertTrue(inbox.delivered().isEmpty());
    }

}
