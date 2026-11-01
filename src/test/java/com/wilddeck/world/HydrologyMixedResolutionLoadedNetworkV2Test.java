package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionLoadedNetworkV2Test {
    @TempDir Path temp;

    private static GlobalHydrologyAddressV2.Sample coarse(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(3,4,x,y);
    }
    private static HydrologyMixedResolutionEpochV2.Receipt receipt(
            GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologyMixedResolutionEpochV2.Receipt(1,from,to);
    }
    private static final HydrologyMixedResolutionEpochV2.Receipt
        WEST = receipt(coarse(4,4),coarse(5,4)),
        NORTH = receipt(coarse(5,5),coarse(5,4)),
        EAST = receipt(coarse(7,4),coarse(8,4));

    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint initial() {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(0,"initial",
            new HydrologyMixedResolutionEpochV2.Snapshot(0,Map.of(),Set.of()));
    }

    private Path sender(String name,
            Map<HydrologyMixedResolutionEpochV2.Receipt,Double> transfers,
            Set<HydrologyMixedResolutionEpochV2.Receipt> acknowledged) throws IOException {
        var file = temp.resolve(name+".bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file,
            new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
                1,"sender",new HydrologyMixedResolutionEpochV2.Snapshot(
                    1,Map.of(),Set.of()),transfers,acknowledged));
        return file;
    }

    private Path receiver(String name,
            HydrologyMixedResolutionEpochV2.Snapshot inbox) throws IOException {
        var file = temp.resolve(name+".bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file,
            new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
                inbox.advancedThrough(),"receiver",inbox));
        return file;
    }

    private Path emptyReceiver(String name) throws IOException {
        return receiver(name,initial().inbox());
    }

    private static HydrologyMixedResolutionLoadedNetworkV2.Receiver owned(
            Path file,GlobalHydrologyAddressV2.Sample... vertices) {
        return new HydrologyMixedResolutionLoadedNetworkV2.Receiver(file,List.of(vertices));
    }

    @Test void confluenceAcrossTwoSendersAndUnloadedDestinationConservesReceipts()
            throws IOException {
        var first = sender("first",Map.of(WEST,7.0,EAST,13.0),Set.of());
        var second = sender("second",Map.of(NORTH,11.0),Set.of());
        var west = emptyReceiver("west");
        var senders = Map.of("second",second,"first",first);
        var loaded = Map.of("west",owned(west,fine(10,8)));
        var before = HydrologyMixedResolutionLoadedNetworkV2.plan(senders,loaded);
        assertEquals(3,before.report().transferCount());
        assertEquals(2,before.pending().size());
        assertEquals(1,before.report().unloadedCount());
        assertEquals(31.0,before.report().exported().doubleValue());
        assertEquals(18.0,before.report().loaded().doubleValue());
        assertEquals(13.0,before.report().unloaded().doubleValue());
        assertEquals(0.0,before.report().accepted().doubleValue());
        var result = HydrologyMixedResolutionLoadedNetworkV2.deliver(senders,loaded);
        assertEquals(2,result.newlyAcknowledged());
        assertTrue(result.after().report().loadedSettled());
        assertEquals(18.0,result.after().report().accepted().doubleValue());
        assertEquals(18.0,result.after().report().acknowledged().doubleValue());
        assertEquals(2,HydrologyMixedResolutionCheckpointStoreV2.load(west)
            .inbox().receipts().size());
        assertEquals(Map.of(EAST,13.0),HydrologyMixedResolutionCheckpointStoreV2.pending(
            HydrologyMixedResolutionCheckpointStoreV2.load(first)));
        assertEquals(0,HydrologyMixedResolutionLoadedNetworkV2.deliver(
            senders,loaded).newlyAcknowledged());
        var solved = HydrologyMixedResolutionCheckpointStoreV2.advance(
            west,HydrologyMixedResolutionCheckpointStoreV2.load(west),
            "receiver:1",List.of(new HydrologyMixedResolutionDischargeV2.Node(
                coarse(5,4),3,null,true)),Set.of(coarse(5,4)));
        assertEquals(21.0,solved.flow().terminalOutflow().get(coarse(5,4)));
        assertEquals(2,solved.checkpoint().inbox().delivered().size());
    }

    @Test void newlyLoadedNeighborReceivesParkedWaterExactlyOnce() throws IOException {
        var upstream = sender("upstream",Map.of(EAST,13.0),Set.of());
        var west = emptyReceiver("west");
        var senders = Map.of("upstream",upstream);
        var loadedWest = Map.of("west",owned(west,coarse(5,4)));
        assertEquals(0,HydrologyMixedResolutionLoadedNetworkV2.deliver(
            senders,loadedWest).newlyAcknowledged());
        assertEquals(1,HydrologyMixedResolutionLoadedNetworkV2.plan(
            senders,loadedWest).report().unloadedCount());
        var east = emptyReceiver("east");
        var all = Map.of("west",owned(west,coarse(5,4)),
            "east",owned(east,fine(16,8)));
        assertEquals(1,HydrologyMixedResolutionLoadedNetworkV2.deliver(
            senders,all).newlyAcknowledged());
        assertEquals(13.0,HydrologyMixedResolutionCheckpointStoreV2.load(east)
            .inbox().receipts().get(EAST));
        assertEquals(0,HydrologyMixedResolutionLoadedNetworkV2.deliver(
            senders,all).newlyAcknowledged());
    }

    @Test void partialCrashAfterReceiverPersistenceOnlyAcknowledgesOnRetry()
            throws IOException {
        var upstream = sender("upstream",Map.of(WEST,7.0),Set.of());
        var downstream = emptyReceiver("downstream");
        HydrologyMixedResolutionCheckpointStoreV2.receive(downstream,
            HydrologyMixedResolutionCheckpointStoreV2.load(downstream),WEST,7.0);
        var before = HydrologyMixedResolutionLoadedNetworkV2.plan(
            Map.of("s",upstream),Map.of("r",owned(downstream,coarse(5,4))));
        assertEquals(7.0,before.report().accepted().doubleValue());
        assertEquals(0.0,before.report().acknowledged().doubleValue());
        assertEquals(1,HydrologyMixedResolutionLoadedNetworkV2.deliver(
            Map.of("s",upstream),Map.of("r",owned(downstream,coarse(5,4))))
            .newlyAcknowledged());
        assertEquals(1,HydrologyMixedResolutionCheckpointStoreV2.load(downstream)
            .inbox().receipts().size());
    }

    @Test void invalidLaterReceiverRejectsWholeBatchBeforeAnyWrite()
            throws IOException {
        var upstream = sender("upstream",Map.of(WEST,7.0,EAST,13.0),Set.of());
        var west = emptyReceiver("west");
        var east = receiver("east",new HydrologyMixedResolutionEpochV2.Snapshot(
            0,Map.of(EAST,12.0),Set.of()));
        var initialSender = HydrologyMixedResolutionCheckpointStoreV2.load(upstream);
        var initialWest = HydrologyMixedResolutionCheckpointStoreV2.load(west);
        assertThrows(IOException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.deliver(
                Map.of("s",upstream),Map.of("a",owned(west,coarse(5,4)),
                    "z",owned(east,coarse(8,4)))));
        assertEquals(initialSender,HydrologyMixedResolutionCheckpointStoreV2.load(upstream));
        assertEquals(initialWest,HydrologyMixedResolutionCheckpointStoreV2.load(west));
    }

    @Test void crossResolutionDuplicateOwnershipRejectedBeforeDelivery()
            throws IOException {
        var upstream = sender("upstream",Map.of(WEST,7.0),Set.of());
        var west = emptyReceiver("west");
        var duplicate = emptyReceiver("duplicate");
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.deliver(
                Map.of("s",upstream),Map.of(
                    "a",owned(west,coarse(5,4)),
                    "b",owned(duplicate,fine(10,8)))));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(west)
            .inbox().receipts().isEmpty());
    }

    @Test void forkedPhysicalSourceAcrossSendersRejected() throws IOException {
        var fork = receipt(fine(8,8),fine(9,8));
        var one = sender("one",Map.of(WEST,7.0),Set.of());
        var two = sender("two",Map.of(fork,11.0),Set.of());
        var receiver = emptyReceiver("receiver");
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.plan(
                Map.of("one",one,"two",two),
                Map.of("r",owned(receiver,coarse(5,4)))));
    }

    @Test void lostAcknowledgedReceiptFailsClosed() throws IOException {
        var upstream = sender("upstream",Map.of(WEST,7.0),Set.of(WEST));
        var downstream = emptyReceiver("downstream");
        assertThrows(IOException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.deliver(
                Map.of("s",upstream),Map.of("r",owned(downstream,coarse(5,4)))));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(downstream)
            .inbox().receipts().isEmpty());
    }

    @Test void unprovenReceiverReceiptFailsClosed() throws IOException {
        var upstream = sender("upstream",Map.of(WEST,7.0),Set.of());
        var other = receipt(coarse(6,4),coarse(5,4));
        var downstream = receiver("downstream",
            new HydrologyMixedResolutionEpochV2.Snapshot(
                0,Map.of(other,2.0),Set.of()));
        assertThrows(IOException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.plan(
                Map.of("s",upstream),Map.of("r",owned(downstream,coarse(5,4)))));
    }

    @Test void canonicalOrderIndependentOfInputMapIteration() throws IOException {
        var first = sender("first",Map.of(WEST,7.0),Set.of());
        var second = sender("second",Map.of(EAST,13.0),Set.of());
        var west = emptyReceiver("west");
        var east = emptyReceiver("east");
        var a = HydrologyMixedResolutionLoadedNetworkV2.plan(
            new LinkedHashMap<>(Map.of("second",second,"first",first)),
            Map.of("east",owned(east,coarse(8,4)),
                "west",owned(west,coarse(5,4))));
        var b = HydrologyMixedResolutionLoadedNetworkV2.plan(
            Map.of("first",first,"second",second),
            Map.of("west",owned(west,coarse(5,4)),
                "east",owned(east,coarse(8,4))));
        assertEquals(a,b);
    }

    @Test void aliasedSenderAndReceiverFileRejected() throws IOException {
        var upstream = sender("upstream",Map.of(WEST,7.0),Set.of());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.plan(
                Map.of("s",upstream),Map.of("r",owned(upstream,coarse(5,4)))));
    }

    @Test void loadedSenderRegionCanExportToAnotherLoadedRegion() throws IOException {
        var source = sender("source",Map.of(WEST,7.0),Set.of());
        var destination = emptyReceiver("destination");
        var loaded = Map.of("source",owned(source,coarse(4,4)),
            "destination",owned(destination,coarse(5,4)));
        var senders = Map.of("source",source);
        var result = HydrologyMixedResolutionLoadedNetworkV2.deliver(senders,loaded);
        assertEquals(1,result.newlyAcknowledged());
        assertEquals(7.0,result.after().report().acknowledged().doubleValue());
        assertEquals(1,HydrologyMixedResolutionCheckpointStoreV2.load(destination)
            .inbox().receipts().size());
    }

    @Test void crossNamedFileAliasCannotClaimSenderAndReceiverOwnership()
            throws IOException {
        var source = sender("source",Map.of(WEST,7.0),Set.of());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.plan(
                Map.of("sender",source),
                Map.of("different",owned(source,coarse(4,4)))));
    }

    @Test void acknowledgedButCurrentlyUnloadedDestinationRetainsHistory()
            throws IOException {
        var source = sender("source",Map.of(WEST,7.0),Set.of(WEST));
        var plan = HydrologyMixedResolutionLoadedNetworkV2.plan(
            Map.of("s",source),Map.of());
        assertEquals(1,plan.report().unloadedCount());
        assertTrue(plan.report().loadedSettled());
        assertEquals(7.0,plan.report().unloaded().doubleValue());
        // Reloading the receiver exposes missing acknowledged water.
        var restored = emptyReceiver("restored");
        assertThrows(IOException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.plan(
                Map.of("s",source),Map.of("r",owned(restored,coarse(5,4)))));
    }

    @Test void receiverDeliveredAndParkedWaterAreAuditedSeparately()
            throws IOException {
        var source = sender("source",Map.of(WEST,7.0),Set.of());
        var destination = emptyReceiver("destination");
        var senders = Map.of("s",source);
        var loaded = Map.of("r",owned(destination,coarse(5,4)));
        var before = HydrologyMixedResolutionLoadedNetworkV2.deliver(senders,loaded);
        assertEquals(0.0,before.after().report().delivered().doubleValue());
        assertEquals(7.0,before.after().report().parked().doubleValue());
        HydrologyMixedResolutionCheckpointStoreV2.advance(
            destination,HydrologyMixedResolutionCheckpointStoreV2.load(destination),
            "receiver:1",List.of(new HydrologyMixedResolutionDischargeV2.Node(
                coarse(5,4),0,null,true)),Set.of(coarse(5,4)));
        var after = HydrologyMixedResolutionLoadedNetworkV2.plan(senders,loaded);
        assertEquals(7.0,after.report().delivered().doubleValue());
        assertEquals(0.0,after.report().parked().doubleValue());
        assertEquals(7.0,after.report().accepted().doubleValue());
    }

    @Test void loadedPhysicalSourceCannotBeClaimedByDifferentSenderFile()
            throws IOException {
        var upstream = sender("upstream",Map.of(WEST,7.0),Set.of());
        var source = emptyReceiver("source");
        var destination = emptyReceiver("destination");
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.plan(
                Map.of("upstream",upstream),
                Map.of("source",owned(source,coarse(4,4)),
                    "destination",owned(destination,coarse(5,4)))));
    }

    @Test void localEdgeCannotBeReimportedAsCrossRegionWater()
            throws IOException {
        var source = sender("source",Map.of(WEST,7.0),Set.of());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyMixedResolutionLoadedNetworkV2.plan(
                Map.of("source",source),
                Map.of("source",owned(source,coarse(4,4),coarse(5,4)))));
    }
}
