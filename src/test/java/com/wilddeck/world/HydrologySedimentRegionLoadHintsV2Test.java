package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentRegionLoadHintsV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(
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

    @Test void unloadedDestinationsAreDeduplicatedAndOrderedByRegion()
            throws IOException {
        var toEast=receipt(at(2,2),at(3,2));
        var toEastAgain=receipt(at(3,1),at(3,2));
        var toSouth=receipt(at(0,3),at(1,3));
        var senders=Map.of(
            "b",sender("b",Map.of(toSouth,2.0)),
            "a",sender("a",Map.of(toEast,1.0,toEastAgain,3.0)));
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(1,0),
                new HydrologyRegionSchedulerV2.Region(0,1)),
            HydrologySedimentRegionLoadHintsV2.pendingRegions(senders,List.of()));
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(0,1)),
            HydrologySedimentRegionLoadHintsV2.pendingRegions(
                senders,List.of(at(3,2))));
    }

    @Test void crossResolutionLoadedOwnershipSuppressesAlreadyPresentHint()
            throws IOException {
        var transfer=receipt(at(0,2),at(1,2));
        var senders=Map.of("a",sender("alias",Map.of(transfer,5.0)));
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(0,0)),
            HydrologySedimentRegionLoadHintsV2.pendingRegions(senders,List.of()));
        assertTrue(HydrologySedimentRegionLoadHintsV2.pendingRegions(
            senders,List.of(new GlobalHydrologyAddressV2.Sample(2,4,4,8)))
            .isEmpty());
    }

    @Test void mixedUnloadedDestinationResolutionsFailBeforeLoading()
            throws IOException {
        var low=receipt(at(2,2),at(3,2));
        var high=receipt(
            new GlobalHydrologyAddressV2.Sample(2,4,4,7),
            new GlobalHydrologyAddressV2.Sample(2,4,5,7));
        var senders=Map.of("low",sender("low",Map.of(low,1.0)),
                           "high",sender("high",Map.of(high,1.0)));
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentRegionLoadHintsV2.loadWithPending(
                List.of(),senders,List.of(),0,10,region->{
                    calls.incrementAndGet();
                    return List.of();
                }));
        assertEquals(0,calls.get());
    }

    @Test void duplicateSenderHistoriesRejectWithoutLoading() throws IOException {
        var transfer=receipt(at(2,2),at(3,2));
        var senders=Map.of("one",sender("one",Map.of(transfer,1.0)),
                           "two",sender("two",Map.of(transfer,1.0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentRegionLoadHintsV2.pendingRegions(senders,List.of()));
    }

    @Test void budgetFailureDoesNotInvokeRegionProvider() throws IOException {
        var transfer=receipt(at(2,2),at(3,2));
        var senders=Map.of("one",sender("budget",Map.of(transfer,1.0)));
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentRegionLoadHintsV2.loadWithPending(
                List.of(),senders,List.of(),1,3,region->{
                    calls.incrementAndGet();
                    return List.of();
                }));
        assertEquals(0,calls.get());
    }

    @Test void loadHintsPreserveDeterministicProviderOrder() throws IOException {
        var senders=Map.of("one",sender("order",Map.of(
            receipt(at(2,2),at(3,2)),1.0)));
        var seen=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var loaded=HydrologySedimentRegionLoadHintsV2.loadWithPending(
            List.of(new HydrologyRegionSchedulerV2.Region(0,1)),
            senders,List.of(),0,2,region->{
                seen.add(region);
                return List.of();
            });
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(1,0),
                new HydrologyRegionSchedulerV2.Region(0,1)),seen);
        assertEquals(2,loaded.size());
    }
}
