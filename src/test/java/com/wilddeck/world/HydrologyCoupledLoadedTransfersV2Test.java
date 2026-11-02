package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyCoupledLoadedTransfersV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample SOURCE = at(4,4),
        DEST = at(5,4), UNLOADED = at(8,4);
    private static final HydrologyMixedResolutionEpochV2.Receipt WATER =
        new HydrologyMixedResolutionEpochV2.Receipt(1,SOURCE,DEST);
    private static final HydrologySedimentReceiptInboxV2.Receipt SEDIMENT =
        new HydrologySedimentReceiptInboxV2.Receipt(0,1,SOURCE,DEST);

    private Path waterSender(String name,
            Map<HydrologyMixedResolutionEpochV2.Receipt,Double> outbox)
            throws IOException {
        var path = directory.resolve(name+".water");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,
            new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
                1,"sender",new HydrologyMixedResolutionEpochV2.Snapshot(
                    1,Map.of(),Set.of()),outbox,Set.of()));
        return path;
    }
    private Path waterReceiver(String name) throws IOException {
        var path = directory.resolve(name+".water");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,
            new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
                0,"receiver",new HydrologyMixedResolutionEpochV2.Snapshot(
                    0,Map.of(),Set.of())));
        return path;
    }
    private Path sedimentSender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> outbox)
            throws IOException {
        var path = directory.resolve(name+".sediment");
        var initial = HydrologySedimentSenderOutboxV2.initialize(path);
        double mass = outbox.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(path,initial,
            new HydrologySedimentFrontierTransfersV2.Outgoing(outbox,mass));
        return path;
    }
    private Path sedimentReceiver(String name) throws IOException {
        var path = directory.resolve(name+".sediment");
        HydrologyCheckpointStoreV2.save(path,HydrologyCheckpointV2.capture(
            0,HydrologySedimentCheckpointV2.initialWorld("test-world"),
            new HydrologyEpochInboxV2()));
        return path;
    }
    private static HydrologyCoupledLoadedTransfersV2.Region region(
            Path water,Path sediment,GlobalHydrologyAddressV2.Sample... owned) {
        return new HydrologyCoupledLoadedTransfersV2.Region(water,sediment,List.of(owned));
    }

    @Test void coupledDeliveryPreservesBothBudgetsAndIsReplaySafe() throws IOException {
        var water = waterSender("upstream",Map.of(WATER,7.0));
        var sediment = sedimentSender("upstream",Map.of(SEDIMENT,3.0));
        var destination = region(waterReceiver("downstream"),
            sedimentReceiver("downstream"),DEST);
        var ws = Map.of("upstream",water);
        var ss = Map.of("upstream",sediment);
        var loaded = Map.of("downstream",destination);
        var before = HydrologyCoupledLoadedTransfersV2.plan(ws,ss,loaded);
        assertEquals(1,before.water().pending().size());
        assertEquals(1,before.sediment().pendingReceipts());
        assertEquals(1,before.physicalVertices());
        var result = HydrologyCoupledLoadedTransfersV2.deliver(ws,ss,loaded);
        assertEquals(1,result.water().newlyAcknowledged());
        assertEquals(1,result.sediment().newlyReceived());
        assertEquals(7.0,result.after().water().report().acknowledged().doubleValue());
        assertEquals(3.0,result.sediment().newlyReceivedMass());
        assertTrue(result.after().loadedTransfersSettled());
        assertEquals(1,HydrologyMixedResolutionCheckpointStoreV2.load(
            destination.waterCheckpoint()).inbox().receipts().size());
        var replay = HydrologyCoupledLoadedTransfersV2.deliver(ws,ss,loaded);
        assertEquals(0,replay.water().newlyAcknowledged());
        assertEquals(0,replay.sediment().newlyReceived());
    }

    @Test void invalidSedimentPreflightPreventsWaterWrites() throws IOException {
        var water = waterSender("upstream",Map.of(WATER,7.0));
        var sediment = sedimentSender("upstream",Map.of(SEDIMENT,3.0));
        var destWater = waterReceiver("downstream");
        var destSediment = sedimentReceiver("downstream");
        HydrologyCheckpointStoreV2.saveNext(destSediment,
            HydrologyCheckpointStoreV2.load(destSediment),
            HydrologyCheckpointV2.capture(1,
                HydrologySedimentCheckpointV2.initialWorld("test-world"),
                new HydrologyEpochInboxV2()));
        var before = HydrologyMixedResolutionCheckpointStoreV2.load(destWater);
        assertThrows(IllegalStateException.class,() ->
            HydrologyCoupledLoadedTransfersV2.deliver(
                Map.of("s",water),Map.of("s",sediment),
                Map.of("r",region(destWater,destSediment,DEST))));
        assertEquals(before,HydrologyMixedResolutionCheckpointStoreV2.load(destWater));
    }

    @Test void interruptedAfterWaterDeliveryCanResumeWithoutDuplicateReceipts()
            throws IOException {
        var water = waterSender("upstream",Map.of(WATER,7.0));
        var sediment = sedimentSender("upstream",Map.of(SEDIMENT,3.0));
        var destWater = waterReceiver("downstream");
        var destSediment = sedimentReceiver("downstream");
        var loaded = Map.of("r",region(destWater,destSediment,DEST));
        var ws = Map.of("s",water);
        var ss = Map.of("s",sediment);
        HydrologyMixedResolutionLoadedNetworkV2.deliver(ws,
            Map.of("r",new HydrologyMixedResolutionLoadedNetworkV2.Receiver(
                destWater,List.of(DEST))));
        var resumed = HydrologyCoupledLoadedTransfersV2.deliver(ws,ss,loaded);
        assertEquals(0,resumed.water().newlyAcknowledged());
        assertEquals(1,resumed.sediment().newlyReceived());
        assertEquals(1,HydrologyMixedResolutionCheckpointStoreV2.load(destWater)
            .inbox().receipts().size());
    }

    @Test void unloadedDestinationRemainsQueuedForBothMedia() throws IOException {
        var waterReceipt = new HydrologyMixedResolutionEpochV2.Receipt(1,at(7,4),UNLOADED);
        var sedimentReceipt = new HydrologySedimentReceiptInboxV2.Receipt(
            0,1,at(7,4),UNLOADED);
        var water = waterSender("upstream",Map.of(waterReceipt,8.0));
        var sediment = sedimentSender("upstream",Map.of(sedimentReceipt,2.0));
        var result = HydrologyCoupledLoadedTransfersV2.deliver(
            Map.of("s",water),Map.of("s",sediment),Map.of());
        assertTrue(result.after().loadedTransfersSettled());
        assertEquals(1,result.after().water().report().unloadedCount());
        assertEquals(0,result.water().newlyAcknowledged());
        assertEquals(0,result.sediment().newlyReceived());
        assertEquals(1,HydrologyMixedResolutionCheckpointStoreV2.pending(
            HydrologyMixedResolutionCheckpointStoreV2.load(water)).size());
        assertEquals(0,HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(sediment)).acknowledged().size());
    }

    @Test void sharedPhysicalReceiverAliasesRejectedAcrossRegions() throws IOException {
        var west = region(waterReceiver("west"),sedimentReceiver("west"),DEST);
        var east = region(waterReceiver("east"),sedimentReceiver("east"),
            new GlobalHydrologyAddressV2.Sample(3,4,10,8));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledLoadedTransfersV2.plan(Map.of(),Map.of(),
                Map.of("west",west,"east",east)));
    }

    @Test void waterAndSedimentCannotAliasSameCheckpointPath() throws IOException {
        var water = waterReceiver("receiver");
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledLoadedTransfersV2.plan(Map.of(),Map.of(),
                Map.of("r",region(water,water,DEST))));
    }

    @Test void invalidWaterPreflightPreventsSedimentWrites() throws IOException {
        var water = waterSender("upstream",Map.of(WATER,7.0));
        var sediment = sedimentSender("upstream",Map.of(SEDIMENT,3.0));
        var destWater = waterReceiver("downstream");
        var destSediment = sedimentReceiver("downstream");
        var before = HydrologyCheckpointStoreV2.load(destSediment);
        // The source is loaded but belongs to a different sender checkpoint.
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledLoadedTransfersV2.deliver(
                Map.of("s",water),Map.of("s",sediment),
                Map.of("r",region(destWater,destSediment,SOURCE,DEST))));
        assertEquals(before,HydrologyCheckpointStoreV2.load(destSediment));
    }

    @Test void planIndependentOfCallerMapOrder() throws IOException {
        var water = waterSender("upstream",Map.of(WATER,7.0));
        var sediment = sedimentSender("upstream",Map.of(SEDIMENT,3.0));
        var receiver = region(waterReceiver("downstream"),
            sedimentReceiver("downstream"),DEST);
        var first = HydrologyCoupledLoadedTransfersV2.plan(
            new LinkedHashMap<>(Map.of("s",water)),
            new LinkedHashMap<>(Map.of("s",sediment)),
            new LinkedHashMap<>(Map.of("r",receiver)));
        var second = HydrologyCoupledLoadedTransfersV2.plan(
            Map.of("s",water),Map.of("s",sediment),Map.of("r",receiver));
        assertEquals(first,second);
    }
    @Test void unloadedWaterAndSedimentCrossLodAliasesShareOneHint()
            throws IOException {
        var water = waterSender("hint",Map.of(WATER,7.0));
        var fineSource = new GlobalHydrologyAddressV2.Sample(3,4,9,8);
        var fineDestination = new GlobalHydrologyAddressV2.Sample(3,4,10,8);
        var sediment = sedimentSender("hint",Map.of(
            new HydrologySedimentReceiptInboxV2.Receipt(
                0,1,fineSource,fineDestination),3.0));
        var hints = HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            Map.of("w",water),Map.of("s",sediment),Map.of());
        assertEquals(1,hints.size());
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(DEST),
            hints.get(0).coordinate());
        assertEquals(7.0,hints.get(0).waterVolume().doubleValue());
        assertEquals(3.0,hints.get(0).sedimentMass().doubleValue());
        assertEquals(1,hints.get(0).waterReceipts());
        assertEquals(1,hints.get(0).sedimentReceipts());
    }

    @Test void loadedDestinationsNeverProduceUnloadedHints() throws IOException {
        var water = waterSender("hint",Map.of(WATER,7.0));
        var sediment = sedimentSender("hint",Map.of(SEDIMENT,3.0));
        var loaded = Map.of("r",region(waterReceiver("hint"),
            sedimentReceiver("hint"),DEST));
        assertTrue(HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            Map.of("w",water),Map.of("s",sediment),loaded).isEmpty());
    }

    @Test void acknowledgedWaterRetainsHistoryButIsNotAnUnloadedHint()
            throws IOException {
        var water = waterSender("hint",Map.of(WATER,7.0));
        var destination = waterReceiver("hint");
        HydrologyMixedResolutionLoadedNetworkV2.deliver(
            Map.of("w",water),Map.of("r",
                new HydrologyMixedResolutionLoadedNetworkV2.Receiver(
                    destination,List.of(DEST))));
        assertEquals(1,HydrologyMixedResolutionCheckpointStoreV2.load(water)
            .acknowledged().size());
        assertTrue(HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            Map.of("w",water),Map.of(),Map.of()).isEmpty());
    }

    @Test void multipleUnloadedDestinationsAreDeterministicallySorted()
            throws IOException {
        var second = new HydrologyMixedResolutionEpochV2.Receipt(
            1,at(7,4),UNLOADED);
        var water = waterSender("hint",Map.of(second,8.0,WATER,7.0));
        var a = HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            Map.of("w",water),Map.of(),Map.of());
        var b = HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            new LinkedHashMap<>(Map.of("w",water)),Map.of(),Map.of());
        assertEquals(a,b);
        assertEquals(2,a.size());
        assertTrue(a.get(0).coordinate().stableKey()
            .compareTo(a.get(1).coordinate().stableKey()) < 0);
    }

}
