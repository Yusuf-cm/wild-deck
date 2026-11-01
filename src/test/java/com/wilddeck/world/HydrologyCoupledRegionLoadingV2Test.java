package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyCoupledRegionLoadingV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static HydrologyRegionSchedulerV2.Region region(long x,long y) {
        return new HydrologyRegionSchedulerV2.Region(x,y);
    }

    private static HydrologySedimentReceiptInboxV2.Receipt sediment(
            GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(0,1,from,to);
    }

    private Path sender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> receipts)
            throws IOException {
        Path path=directory.resolve(name+".sender");
        var first=HydrologySedimentSenderOutboxV2.initialize(path);
        double total=receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(path,first,
            new HydrologySedimentFrontierTransfersV2.Outgoing(receipts,total));
        return path;
    }

    private static HydrologyEpochInboxV2.Snapshot water(
            GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,from,to),5);
        return inbox.snapshot();
    }

    @Test void mergesParkedWaterSedimentAndFocusInSphericalRowMajorOrder()
            throws IOException {
        var water=water(at(2,2),at(3,2));
        var senders=Map.of("sediment",sender("joined",
            Map.of(sediment(at(0,3),at(1,3)),2000.0)));
        var plan=HydrologyCoupledRegionLoadingV2.plan(
            List.of(region(3,1),region(3,1)),water,senders,
            List.of(),1,2,0,3);
        assertEquals(List.of(region(1,0),region(0,1),region(3,1)),
            plan.regions());
        assertEquals(1,plan.pendingWaterReceipts());
        assertEquals(1,plan.pendingSedimentReceipts());
        var seen=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var loaded=HydrologyCoupledRegionLoadingV2.load(
            List.of(region(3,1)),water,senders,List.of(),1,2,0,3,r->{
                seen.add(r);
                return List.of();
            });
        assertEquals(plan.regions(),seen);
        assertEquals(3,loaded.size());
    }

    @Test void seamAndPolarNeighborhoodsUseSphericalWrapAndClamp()
            throws IOException {
        var water=water(at(7,3),at(0,3));
        var plan=HydrologyCoupledRegionLoadingV2.plan(
            List.of(),water,Map.of(),List.of(),1,2,1,6);
        assertEquals(List.of(region(0,0),region(1,0),region(3,0),
                             region(0,1),region(1,1),region(3,1)),
            plan.regions());
    }

    @Test void alreadyLoadedPhysicalAliasSuppressesSedimentHint()
            throws IOException {
        var senders=Map.of("a",sender("alias",
            Map.of(sediment(at(0,3),at(1,3)),2.0)));
        var highResolutionAlias=new GlobalHydrologyAddressV2.Sample(2,4,4,12);
        var plan=HydrologyCoupledRegionLoadingV2.plan(
            List.of(),new HydrologyEpochInboxV2().snapshot(),
            senders,List.of(highResolutionAlias),1,2,0,1);
        assertTrue(plan.regions().isEmpty());
        assertEquals(0,plan.pendingSedimentReceipts());
    }

    @Test void mixedSedimentResolutionFailsBeforeProviderIsInvoked()
            throws IOException {
        var highFrom=new GlobalHydrologyAddressV2.Sample(2,4,4,12);
        var highTo=new GlobalHydrologyAddressV2.Sample(2,4,5,12);
        var senders=Map.of("a",sender("wrong-resolution",
            Map.of(sediment(highFrom,highTo),1.0)));
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyCoupledRegionLoadingV2.load(
                List.of(),new HydrologyEpochInboxV2().snapshot(),
                senders,List.of(),1,2,0,3,r->{
                    calls.incrementAndGet();
                    return List.of();
                }));
        assertEquals(0,calls.get());
    }

    @Test void mismatchedWaterDivisionsFailBeforeProviderIsInvoked()
            throws IOException {
        var from=new GlobalHydrologyAddressV2.Sample(1,4,2,3);
        var to=new GlobalHydrologyAddressV2.Sample(1,4,3,3);
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyCoupledRegionLoadingV2.load(
                List.of(),water(from,to),Map.of(),List.of(),
                1,2,0,3,r->{
                    calls.incrementAndGet();
                    return List.of();
                }));
        assertEquals(0,calls.get());
    }

    @Test void duplicateJournalPathAndPhysicalForkRejectWithoutLoading()
            throws IOException {
        var first=sediment(at(0,3),at(1,3));
        var second=sediment(at(0,3),at(7,3));
        var a=sender("one",Map.of(first,1.0));
        var b=sender("two",Map.of(second,2.0));
        var empty=new HydrologyEpochInboxV2().snapshot();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyCoupledRegionLoadingV2.plan(
                List.of(),empty,Map.of("a",a,"alias",a),
                List.of(),1,2,0,5));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyCoupledRegionLoadingV2.plan(
                List.of(),empty,Map.of("a",a,"b",b),
                List.of(),1,2,0,5));
    }

    @Test void budgetFailureInvokesNoProviderAndLeavesJournalUntouched()
            throws IOException {
        var sender=sender("budget",
            Map.of(sediment(at(0,3),at(1,3)),1.0));
        var before=HydrologyCheckpointStoreV2.load(sender);
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->
            HydrologyCoupledRegionLoadingV2.load(
                List.of(),water(at(2,2),at(3,2)),
                Map.of("a",sender),List.of(),1,2,0,1,r->{
                    calls.incrementAndGet();
                    return List.of();
                }));
        assertEquals(0,calls.get());
        assertEquals(before,HydrologyCheckpointStoreV2.load(sender));
    }

    @Test void runnerPlansAndLoadsWithoutAdvancingOrConsumingReceipts()
            throws IOException {
        var checkpoint=directory.resolve("runner.chk");
        var runner=HydrologyWorldRunnerV2.open(checkpoint,0,"initial");
        var waterKey=new HydrologyTransferLedgerV2.Key(1,at(2,2),at(3,2));
        assertTrue(runner.receive(waterKey,5));
        var sender=sender("runner",Map.of(sediment(at(0,3),at(1,3)),10.0));
        var original=HydrologyCheckpointStoreV2.load(checkpoint);
        var originalSender=HydrologyCheckpointStoreV2.load(sender);
        var plan=runner.planPendingWaterAndSedimentRegions(
            List.of(),Map.of("s",sender),List.of(),1,2,0,2);
        assertEquals(List.of(region(1,0),region(0,1)),plan.regions());
        var seen=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        runner.loadPendingWaterAndSedimentRegions(
            List.of(),Map.of("s",sender),List.of(),1,2,0,2,r->{
                seen.add(r);
                return List.of();
            });
        assertEquals(plan.regions(),seen);
        assertEquals(0,runner.epoch());
        assertEquals(Set.of(waterKey),runner.inboxSnapshot().receipts().keySet());
        assertTrue(runner.inboxSnapshot().delivered().isEmpty());
        assertEquals(original,HydrologyCheckpointStoreV2.load(checkpoint));
        assertEquals(originalSender,HydrologyCheckpointStoreV2.load(sender));
    }

    @Test void staleRunnerCannotLoadFromObsoleteWaterCheckpoint()
            throws IOException {
        var checkpoint=directory.resolve("stale.chk");
        var stale=HydrologyWorldRunnerV2.open(checkpoint,0,"initial");
        var current=HydrologyWorldRunnerV2.open(checkpoint,0,"ignored");
        current.receive(new HydrologyTransferLedgerV2.Key(1,at(2,2),at(3,2)),2);
        var calls=new AtomicInteger();
        assertThrows(IOException.class,()->
            stale.loadPendingWaterAndSedimentRegions(
                List.of(),Map.of(),List.of(),1,2,0,3,r->{
                    calls.incrementAndGet();
                    return List.of();
                }));
        assertEquals(0,calls.get());
    }
}
