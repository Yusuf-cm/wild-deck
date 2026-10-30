package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyDrainageTransitionReportV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var result=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),1));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            result.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),1));
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),1));
        return result;
    }
    private static HydrologySphericalRouteAtlasV2 atlas() {
        return HydrologySphericalRouteAtlasV2.compile(planet(),
            a->a.equals(at(0,2))||a.equals(at(4,2))?0:10,Set.of(at(0,2)));
    }
    private static HydrologySphericalAtlasRebuildAuditV2.Result audit(
            HydrologySphericalRouteAtlasV2 previous,boolean changed) {
        return HydrologySphericalAtlasRebuildAuditV2.rebuild(previous,planet(),
            a->a.equals(at(0,2))||a.equals(at(4,2))?0:10,
            changed?Set.of(at(0,2),at(4,2)):Set.of(at(0,2)));
    }
    @Test void inventoriesBlockedWaterWithoutConsumingIt() {
        var previous=atlas();
        var from=at(4,2);
        var key=new HydrologyTransferLedgerV2.Key(
            1,from,previous.routeAt(from).downstream());
        var inbox=new HydrologyEpochInboxV2();
        assertTrue(inbox.receive(key,11));
        var before=inbox.snapshot();
        var unchanged=HydrologyDrainageTransitionReportV2.inspect(
            previous,audit(previous,false),before);
        assertTrue(unchanged.safeForPendingWater());
        assertEquals(List.of(key),unchanged.reusable());
        assertEquals(11,unchanged.reusableVolume(),1e-12);
        assertEquals(0,unchanged.blockedVolume(),1e-12);
        assertEquals(unchanged.previousTopology(),unchanged.rebuiltTopology());

        var changed=HydrologyDrainageTransitionReportV2.inspect(
            previous,audit(previous,true),before);
        assertFalse(changed.safeForPendingWater());
        assertEquals(List.of(key),changed.blocked());
        assertEquals(11,changed.blockedVolume(),1e-12);
        assertNotEquals(changed.previousTopology(),changed.rebuiltTopology());
        assertEquals(before,inbox.snapshot());
    }
    @Test void deliveredReceiptsAreExcludedAndNotReplayed() {
        var previous=atlas();
        var from=at(4,2);
        var key=new HydrologyTransferLedgerV2.Key(
            1,from,previous.routeAt(from).downstream());
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(key,7);
        inbox.claimAvailable(1,Set.of(key.to()));
        var report=HydrologyDrainageTransitionReportV2.inspect(
            previous,audit(previous,true),inbox.snapshot());
        assertTrue(report.safeForPendingWater());
        assertTrue(report.reusable().isEmpty());
        assertTrue(report.blocked().isEmpty());
        assertEquals(0,report.blockedVolume(),1e-12);
        assertEquals(7,HydrologyReceiptBudgetV2.summarize(inbox.snapshot())
            .deliveredVolume(),1e-12);
    }
}
