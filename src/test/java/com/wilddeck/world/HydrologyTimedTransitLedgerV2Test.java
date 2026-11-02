package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTimedTransitLedgerV2Test {
    private static final HydrologyGlobalCatchmentsV2.Coordinate A =
        new HydrologyGlobalCatchmentsV2.Coordinate(1,4,1,2);
    private static final HydrologyGlobalCatchmentsV2.Coordinate B =
        new HydrologyGlobalCatchmentsV2.Coordinate(1,2,1,2);

    private static HydrologyTimedTransitLedgerV2.Transfer transfer(
            String id,HydrologyGlobalCatchmentsV2.Coordinate to,long due,double m3) {
        return new HydrologyTimedTransitLedgerV2.Transfer(id,to,due,m3);
    }

    @Test void retainsAbsoluteDueTickThroughUnloadedSeasonsAndRestart() {
        var first=HydrologyTimedTransitLedgerV2.advance(
            HydrologyTimedTransitLedgerV2.empty("world"),
            List.of(transfer("river-1",A,8,3)),7,Set.of(A));
        assertEquals(Map.of(),first.releasedM3());
        var second=HydrologyTimedTransitLedgerV2.advance(
            first.state(),List.of(),9,Set.of());
        assertEquals(8,second.state().pending().get("river-1").dueTick());
        var restarted=HydrologyTimedTransitLedgerV2.decode(
            HydrologyTimedTransitLedgerV2.encode(second.state()));
        var delivered=HydrologyTimedTransitLedgerV2.advance(
            restarted,List.of(),11,Set.of(A));
        assertEquals(Map.of(A,3.0),delivered.releasedM3());
        assertTrue(delivered.state().pending().isEmpty());
        assertEquals(new BigDecimal("3.0"),delivered.releasedTotalM3());
    }

    @Test void idempotentReplayAndConflictProtectionSurviveRestart() {
        var receipt=transfer("boundary-1",B,4,2);
        var first=HydrologyTimedTransitLedgerV2.advance(
            HydrologyTimedTransitLedgerV2.empty("world"),
            List.of(receipt,receipt),5,Set.of(B));
        assertEquals(Map.of(B,2.0),first.releasedM3());
        var state=HydrologyTimedTransitLedgerV2.decode(
            HydrologyTimedTransitLedgerV2.encode(first.state()));
        var replay=HydrologyTimedTransitLedgerV2.advance(
            state,List.of(receipt),5,Set.of(B));
        assertTrue(replay.releasedM3().isEmpty());
        assertEquals(BigDecimal.ZERO,replay.newlyQueuedM3());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedTransitLedgerV2.advance(state,
                List.of(transfer("boundary-1",B,4,3)),6,Set.of(B)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedTransitLedgerV2.advance(
                HydrologyTimedTransitLedgerV2.empty("world"),
                List.of(receipt,transfer("boundary-1",A,4,2)),5,Set.of(A,B)));
    }

    @Test void deterministicConfluenceAndExactMassBalance() {
        var t1=transfer("a",A,2,0.1);
        var t2=transfer("b",A,2,0.2);
        var t3=transfer("c",B,3,5);
        var empty=HydrologyTimedTransitLedgerV2.empty("world");
        var ordered=HydrologyTimedTransitLedgerV2.advance(
            empty,List.of(t1,t2,t3),3,Set.of(A));
        var reversed=HydrologyTimedTransitLedgerV2.advance(
            empty,List.of(t3,t2,t1),3,Set.of(A));
        assertEquals(ordered,reversed);
        assertEquals(Map.of(A,0.3),ordered.releasedM3());
        assertEquals(new BigDecimal("5.0"),ordered.remainingM3());
        assertEquals(0,ordered.previousPendingM3().compareTo(BigDecimal.ZERO));
        assertEquals(0,ordered.newlyQueuedM3().compareTo(
            ordered.releasedTotalM3().add(ordered.remainingM3())));
        var next=HydrologyTimedTransitLedgerV2.advance(
            ordered.state(),List.of(),4,Set.of(B));
        assertEquals(Map.of(B,5.0),next.releasedM3());
    }

    @Test void rejectsLostPrecisionOverflowAndBackwardTime() {
        var huge=transfer("huge",A,1,1e20);
        var tiny=transfer("tiny",A,1,1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedTransitLedgerV2.advance(
                HydrologyTimedTransitLedgerV2.empty("world"),
                List.of(huge,tiny),1,Set.of(A)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedTransitLedgerV2.advance(
                HydrologyTimedTransitLedgerV2.empty("world"),
                List.of(transfer("huge-1",A,1,Double.MAX_VALUE),
                        transfer("huge-2",A,1,Double.MAX_VALUE)),1,Set.of(A)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedTransitLedgerV2.advance(
                HydrologyTimedTransitLedgerV2.empty("world"),List.of(),-1,Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTimedTransitLedgerV2.State("world",0,Map.of(),
                Map.of("future",transfer("future",A,1,1))));
    }

    @Test void canonicalCodecRejectsTamperingAndOverlappingIds() {
        var pending=transfer("p",A,9,3);
        var state=HydrologyTimedTransitLedgerV2.advance(
            HydrologyTimedTransitLedgerV2.empty("world"),
            List.of(pending),2,Set.of()).state();
        var encoded=HydrologyTimedTransitLedgerV2.encode(state);
        assertEquals(state,HydrologyTimedTransitLedgerV2.decode(encoded));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedTransitLedgerV2.decode(
                encoded.replace("WD-TIMED-TRANSIT-1","WD-TIMED-TRANSIT-2")));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTimedTransitLedgerV2.decode(encoded.replace("0x1.8p1","NaN")));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTimedTransitLedgerV2.State("world",9,
                Map.of("p",pending),Map.of("p",pending)));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyTimedTransitLedgerV2.Transfer("bad|id",A,1,1));
    }
}
