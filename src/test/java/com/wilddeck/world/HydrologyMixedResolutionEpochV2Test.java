package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionEpochV2Test {
    private static GlobalHydrologyAddressV2.Sample coarse(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(3,4,x,y);
    }
    private static HydrologyMixedResolutionEpochV2.Receipt receipt(long epoch,
            GlobalHydrologyAddressV2.Sample from,GlobalHydrologyAddressV2.Sample to) {
        return new HydrologyMixedResolutionEpochV2.Receipt(epoch,from,to);
    }
    private static HydrologyMixedResolutionDischargeV2.Node node(
            GlobalHydrologyAddressV2.Sample at,double volume,
            GlobalHydrologyAddressV2.Sample next,boolean terminal) {
        return new HydrologyMixedResolutionDischargeV2.Node(at,volume,next,terminal);
    }

    @Test void mixedLodFrontierDeliveredOnceAcrossSnapshotRestoreAndEpochs() {
        var from=coarse(4,4); var landing=coarse(5,4); var mouth=fine(12,8);
        var key=receipt(3,from,fine(10,8));
        var snapshot=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.empty(),key,7);
        assertSame(snapshot,HydrologyMixedResolutionEpochV2.receive(
            snapshot,receipt(3,fine(8,8),landing),7));
        var restored=HydrologyMixedResolutionEpochV2.decode(
            HydrologyMixedResolutionEpochV2.encode(snapshot));
        assertEquals(snapshot,restored);
        var graph=List.of(node(landing,3,mouth,false),node(mouth,5,null,true));
        var first=HydrologyMixedResolutionEpochV2.prepare(restored,3,graph,Set.of(mouth));
        assertEquals(15.0,first.flow().terminalOutflow().get(mouth));
        assertEquals(Set.of(key),first.next().delivered());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.prepare(first.next(),3,graph,Set.of(mouth)));
        var next=HydrologyMixedResolutionEpochV2.prepare(
            HydrologyMixedResolutionEpochV2.decode(
                HydrologyMixedResolutionEpochV2.encode(first.next())),4,graph,Set.of(mouth));
        assertEquals(8.0,next.flow().terminalOutflow().get(mouth));
        assertEquals(Set.of(key),next.next().delivered());
    }

    @Test void pendingUnloadedReceiptsAreRetainedUntilLaterEpoch() {
        var from=coarse(4,4); var landing=coarse(5,4); var other=coarse(9,4);
        var key=receipt(1,from,landing);
        var state=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.empty(),key,9);
        var early=HydrologyMixedResolutionEpochV2.prepare(state,1,
            List.of(node(other,2,null,true)),Set.of(other));
        assertEquals(2.0,early.flow().terminalOutflow().get(other));
        assertTrue(early.next().delivered().isEmpty());
        var later=HydrologyMixedResolutionEpochV2.prepare(early.next(),2,
            List.of(node(landing,1,null,true)),Set.of(landing));
        assertEquals(10.0,later.flow().terminalOutflow().get(landing));
        assertTrue(later.next().delivered().contains(key));
    }

    @Test void futureReceiptsRemainPendingUntilDue() {
        var from=coarse(4,4);var landing=coarse(5,4);
        var state=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.empty(),receipt(9,from,landing),7);
        var graph=List.of(node(landing,2,null,true));
        var early=HydrologyMixedResolutionEpochV2.prepare(state,8,graph,Set.of(landing));
        assertEquals(2.0,early.flow().terminalOutflow().get(landing));
        assertTrue(early.next().delivered().isEmpty());
        var due=HydrologyMixedResolutionEpochV2.prepare(early.next(),9,graph,Set.of(landing));
        assertEquals(9.0,due.flow().terminalOutflow().get(landing));
    }

    @Test void loadedSourceCannotBeCountedAgainAsImportedReceipt() {
        var from=coarse(4,4);var landing=coarse(5,4);
        var state=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.empty(),receipt(0,from,landing),7);
        var graph=List.of(node(from,7,landing,false),node(landing,3,null,true));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.prepare(state,0,graph,Set.of(landing)));
        assertTrue(state.delivered().isEmpty());
    }

    @Test void aliasesCannotForkOrConflictinglyReplaySourceWater() {
        var a=coarse(4,4);var b=coarse(5,4);var c=coarse(5,5);
        var state=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.empty(),receipt(0,a,b),7);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.receive(state,receipt(0,fine(8,8),fine(10,8)),8));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.receive(state,receipt(0,a,c),7));
        assertEquals(1,state.receipts().size());
    }

    @Test void overflowAndBadGraphDoNotConsumeReceipts() {
        var from=coarse(4,4);var landing=coarse(5,4);
        var state=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.empty(),receipt(0,from,landing),Double.MAX_VALUE);
        var graph=List.of(node(landing,Double.MAX_VALUE,null,true));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.prepare(state,0,graph,Set.of(landing)));
        assertTrue(state.delivered().isEmpty());
        assertEquals(-1,state.advancedThrough());
    }

    @Test void deterministicCodecRejectsCorruptionAndInvalidEdges() {
        var a=coarse(4,4);var b=coarse(5,4);var c=coarse(9,4);var d=coarse(10,4);
        var first=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.receive(
                HydrologyMixedResolutionEpochV2.empty(),receipt(2,a,b),4),receipt(3,c,d),5);
        var second=HydrologyMixedResolutionEpochV2.receive(
            HydrologyMixedResolutionEpochV2.receive(
                HydrologyMixedResolutionEpochV2.empty(),receipt(3,c,d),5),receipt(2,a,b),4);
        assertEquals(HydrologyMixedResolutionEpochV2.encode(first),
                     HydrologyMixedResolutionEpochV2.encode(second));
        var encoded=HydrologyMixedResolutionEpochV2.encode(first);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.decode(encoded+"A"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.decode("invalid"));
        assertThrows(IllegalArgumentException.class,()->receipt(0,a,coarse(15,4)));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyMixedResolutionEpochV2.Snapshot(0,Map.of(),Set.of(receipt(0,a,b))));
    }
}
