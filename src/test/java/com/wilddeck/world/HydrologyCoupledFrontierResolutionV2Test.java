package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyCoupledFrontierResolutionV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A = at(5,4), B = at(8,4);
    private static final HydrologyMixedResolutionEpochV2.Receipt TO_A =
        new HydrologyMixedResolutionEpochV2.Receipt(1,at(4,4),A);
    private static final HydrologyMixedResolutionEpochV2.Receipt TO_B =
        new HydrologyMixedResolutionEpochV2.Receipt(1,at(7,4),B);

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
    private HydrologyCoupledLoadedTransfersV2.Region receiver(String name,
            GlobalHydrologyAddressV2.Sample... owned) throws IOException {
        var water = directory.resolve(name+".water");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(water,
            new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
                0,"receiver",new HydrologyMixedResolutionEpochV2.Snapshot(
                    0,Map.of(),Set.of())));
        var sediment = directory.resolve(name+".sediment");
        HydrologyCheckpointStoreV2.save(sediment,HydrologyCheckpointV2.capture(
            0,HydrologySedimentCheckpointV2.initialWorld("test-world"),
            new HydrologyEpochInboxV2()));
        return new HydrologyCoupledLoadedTransfersV2.Region(water,sediment,List.of(owned));
    }
    private static HydrologyGlobalCatchmentsV2.Coordinate physical(
            GlobalHydrologyAddressV2.Sample sample) {
        return HydrologyGlobalCatchmentsV2.coordinate(sample);
    }
    private static HydrologyCoupledFrontierResolutionV2.Directory directoryFor(
            HydrologyCoupledLoadedTransfersV2.Region a,
            HydrologyCoupledLoadedTransfersV2.Region b) {
        return coordinate -> coordinate.equals(physical(A))
            ? new HydrologyCoupledFrontierResolutionV2.Entry("a",a)
            : new HydrologyCoupledFrontierResolutionV2.Entry("b",b);
    }

    @Test void crossResolutionWaterAndSedimentShareOnePhysicalRegion()
            throws IOException {
        var water = waterSender("sender",Map.of(TO_A,7.0));
        var fineFrom = new GlobalHydrologyAddressV2.Sample(3,4,9,8);
        var fineTo = new GlobalHydrologyAddressV2.Sample(3,4,10,8);
        var sediment = sedimentSender("sender",Map.of(
            new HydrologySedimentReceiptInboxV2.Receipt(0,1,fineFrom,fineTo),3.0));
        var region = receiver("destination",fineTo);
        var ws = Map.of("sender",water);
        var ss = Map.of("sender",sediment);
        var result = HydrologyCoupledFrontierResolutionV2.deliverAll(
            ws,ss,Map.of(),coordinate ->
                new HydrologyCoupledFrontierResolutionV2.Entry("destination",region),1);
        assertTrue(result.plan().complete());
        assertEquals(1,result.plan().resolvedFrontiers());
        assertEquals(List.of("destination"),result.plan().addedRegions());
        assertEquals(1,result.delivery().water().newlyAcknowledged());
        assertEquals(1,result.delivery().sediment().newlyReceived());
        assertEquals(7.0,result.delivery().after().water().report().acknowledged().doubleValue());
        assertTrue(HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            ws,ss,result.plan().loaded()).isEmpty());
        var retry = HydrologyCoupledFrontierResolutionV2.deliverAll(
            ws,ss,result.plan().loaded(),coordinate -> {
                fail("No unresolved directory lookup after successful delivery");
                return null;
            },0);
        assertEquals(0,retry.delivery().water().newlyAcknowledged());
        assertEquals(0,retry.delivery().sediment().newlyReceived());
    }

    @Test void boundedBatchesDeferAndLaterDeliverWithoutDroppingWater()
            throws IOException {
        var water = waterSender("two",Map.of(TO_A,7.0,TO_B,8.0));
        var a = receiver("a",A);
        var b = receiver("b",B);
        var ws = Map.of("s",water);
        var dir = directoryFor(a,b);
        var first = HydrologyCoupledFrontierResolutionV2.deliverBatch(
            ws,Map.of(),Map.of(),dir,1);
        assertEquals(1,first.plan().addedRegions().size());
        assertEquals(1,first.plan().resolvedFrontiers());
        assertEquals(1,first.plan().deferred().size());
        assertEquals(1,first.delivery().water().newlyAcknowledged());
        var second = HydrologyCoupledFrontierResolutionV2.deliverBatch(
            ws,Map.of(),first.plan().loaded(),dir,1);
        assertTrue(second.plan().complete());
        assertEquals(1,second.delivery().water().newlyAcknowledged());
        assertEquals(15.0,second.delivery().after().water().report().acknowledged().doubleValue());
        assertEquals(2,second.plan().loaded().size());
        var replay = HydrologyCoupledFrontierResolutionV2.deliverBatch(
            ws,Map.of(),second.plan().loaded(),dir,0);
        assertEquals(0,replay.delivery().water().newlyAcknowledged());
    }

    @Test void strictBudgetRejectsBeforeAnyWaterOrSedimentWrite()
            throws IOException {
        var water = waterSender("two",Map.of(TO_A,7.0,TO_B,8.0));
        var sediment = sedimentSender("s",Map.of(
            new HydrologySedimentReceiptInboxV2.Receipt(0,1,at(4,4),A),3.0));
        var a = receiver("a",A);
        var b = receiver("b",B);
        var before = HydrologyMixedResolutionCheckpointStoreV2.load(a.waterCheckpoint());
        assertThrows(IllegalStateException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverAll(
                Map.of("w",water),Map.of("s",sediment),Map.of(),directoryFor(a,b),1));
        assertEquals(before,HydrologyMixedResolutionCheckpointStoreV2.load(a.waterCheckpoint()));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water).acknowledged().isEmpty());
        assertTrue(HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(sediment)).acknowledged().isEmpty());
    }

    @Test void directoryMustActuallyOwnThePendingPhysicalVertex() throws IOException {
        var water = waterSender("one",Map.of(TO_A,7.0));
        var wrong = receiver("wrong",B);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatch(
                Map.of("s",water),Map.of(),Map.of(),coordinate ->
                    new HydrologyCoupledFrontierResolutionV2.Entry("wrong",wrong),1));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water).acknowledged().isEmpty());
    }

    @Test void conflictingDirectoryRegionIdentityFailsBeforeWrites() throws IOException {
        var water = waterSender("two",Map.of(TO_A,7.0,TO_B,8.0));
        var a = receiver("a",A);
        var b = receiver("b",B);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatch(
                Map.of("s",water),Map.of(),Map.of(),coordinate ->
                    new HydrologyCoupledFrontierResolutionV2.Entry("same",
                        coordinate.equals(physical(A)) ? a : b),2));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water).acknowledged().isEmpty());
    }

    @Test void overlappingDirectoryOwnershipFailsEvenIfSecondRegionDeferred()
            throws IOException {
        var water = waterSender("two",Map.of(TO_A,7.0,TO_B,8.0));
        var shared = receiver("shared",A,B);
        var b = receiver("b",B);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.planBatch(
                Map.of("s",water),Map.of(),Map.of(),directoryFor(shared,b),1));
    }

    @Test void missingDirectoryMappingFailsClosed() throws IOException {
        var water = waterSender("one",Map.of(TO_A,7.0));
        assertThrows(NullPointerException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatch(
                Map.of("s",water),Map.of(),Map.of(),coordinate -> null,1));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water).acknowledged().isEmpty());
    }

    @Test void negativeBudgetRejectedWithoutDirectoryLookups() throws IOException {
        var water = waterSender("one",Map.of(TO_A,7.0));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.planBatch(
                Map.of("s",water),Map.of(),Map.of(),coordinate -> {
                    fail("Directory must not run for invalid budget");
                    return null;
                },-1));
    }

    @Test void existingLoadedOwnerCannotBeSilentlyReplaced() throws IOException {
        var water = waterSender("one",Map.of(TO_A,7.0,TO_B,8.0));
        var a = receiver("a",A);
        var b = receiver("b",B);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.planBatch(
                Map.of("s",water),Map.of(),Map.of("a",a),coordinate ->
                    new HydrologyCoupledFrontierResolutionV2.Entry("a",b),1));
    }

    @Test void completeDrainBatchesWaterAndSedimentWithOneNewRegionPerBatch()
            throws IOException {
        var water = waterSender("drain",Map.of(TO_A,7.0,TO_B,8.0));
        var sediment = sedimentSender("drain",Map.of(
            new HydrologySedimentReceiptInboxV2.Receipt(0,1,at(4,4),A),3.0));
        var a = receiver("drain-a",A);
        var b = receiver("drain-b",B);
        var ws = Map.of("water",water);
        var ss = Map.of("sediment",sediment);
        var drained = HydrologyCoupledFrontierResolutionV2.deliverBatches(
            ws,ss,Map.of(),directoryFor(a,b),1,2);
        assertEquals(2,drained.batches().size());
        assertEquals(2,drained.loaded().size());
        assertEquals(2,drained.preflight().addedRegions().size());
        assertEquals(1,drained.batches().get(0).plan().deferred().size());
        assertTrue(drained.batches().get(1).plan().complete());
        assertEquals(15.0,drained.batches().get(1).delivery().after()
            .water().report().acknowledged().doubleValue());
        assertEquals(1,drained.batches().stream()
            .mapToInt(batch -> batch.delivery().sediment().newlyReceived()).sum());
        assertTrue(HydrologyCoupledLoadedTransfersV2.pendingUnloaded(
            ws,ss,drained.loaded()).isEmpty());
    }

    @Test void insufficientTotalBatchCapacityFailsBeforeWritingAnyReceipts()
            throws IOException {
        var water = waterSender("capacity",Map.of(TO_A,7.0,TO_B,8.0));
        var a = receiver("capacity-a",A);
        var b = receiver("capacity-b",B);
        var original = HydrologyMixedResolutionCheckpointStoreV2.load(a.waterCheckpoint());
        assertThrows(IllegalStateException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatches(
                Map.of("sender",water),Map.of(),Map.of(),directoryFor(a,b),1,1));
        assertEquals(original,HydrologyMixedResolutionCheckpointStoreV2.load(a.waterCheckpoint()));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water).acknowledged().isEmpty());
    }

    @Test void invalidLaterDirectoryRegionFailsBeforeFirstBatch()
            throws IOException {
        var water = waterSender("invalid-later",Map.of(TO_A,7.0,TO_B,8.0));
        var a = receiver("invalid-a",A);
        var wrong = receiver("invalid-b",at(9,4));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatches(
                Map.of("sender",water),Map.of(),Map.of(),coordinate ->
                    coordinate.equals(physical(A))
                        ? new HydrologyCoupledFrontierResolutionV2.Entry("a",a)
                        : new HydrologyCoupledFrontierResolutionV2.Entry("b",wrong),
                1,2));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water).acknowledged().isEmpty());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(a.waterCheckpoint())
            .inbox().receipts().isEmpty());
    }

    @Test void zeroLoadingCapacityCanDeliverToAlreadyLoadedOwner()
            throws IOException {
        var water = waterSender("loaded-only",Map.of(TO_A,7.0));
        var a = receiver("loaded-only-a",A);
        var result = HydrologyCoupledFrontierResolutionV2.deliverBatches(
            Map.of("sender",water),Map.of(),Map.of("a",a),
            coordinate -> {
                fail("Already loaded physical destination must not be resolved again");
                return null;
            },0,1);
        assertEquals(1,result.batches().size());
        assertEquals(0,result.preflight().addedRegions().size());
        assertEquals(1,result.batches().get(0).delivery().water().newlyAcknowledged());
    }

    @Test void replayOfCompletedBatchesNeverDuplicatesReceipts() throws IOException {
        var water = waterSender("replay",Map.of(TO_A,7.0,TO_B,8.0));
        var a = receiver("replay-a",A);
        var b = receiver("replay-b",B);
        var senders = Map.of("sender",water);
        var first = HydrologyCoupledFrontierResolutionV2.deliverBatches(
            senders,Map.of(),Map.of(),directoryFor(a,b),1,2);
        var second = HydrologyCoupledFrontierResolutionV2.deliverBatches(
            senders,Map.of(),first.loaded(),coordinate -> {
                fail("No pending unloaded frontiers after first drain");
                return null;
            },0,1);
        assertEquals(0,second.batches().get(0).delivery().water().newlyAcknowledged());
        assertEquals(15.0,second.batches().get(0).delivery().after()
            .water().report().acknowledged().doubleValue());
    }

    @Test void invalidBatchBoundsRejectWithoutDirectoryLookup() throws IOException {
        var water = waterSender("bad-bounds",Map.of(TO_A,7.0));
        HydrologyCoupledFrontierResolutionV2.Directory forbidden = coordinate -> {
            fail("Invalid bounds must fail before resolving a directory");
            return null;
        };
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatches(
                Map.of("sender",water),Map.of(),Map.of(),forbidden,-1,1));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatches(
                Map.of("sender",water),Map.of(),Map.of(),forbidden,1,0));
        assertThrows(ArithmeticException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatches(
                Map.of("sender",water),Map.of(),Map.of(),forbidden,
                Integer.MAX_VALUE,2));
    }

    @Test void frozenDirectoryIsResolvedOnlyDuringWholeFrontierPreflight()
            throws IOException {
        var water = waterSender("frozen",Map.of(TO_A,7.0,TO_B,8.0));
        var a = receiver("frozen-a",A);
        var b = receiver("frozen-b",B);
        int[] calls = {0};
        var drained = HydrologyCoupledFrontierResolutionV2.deliverBatches(
            Map.of("sender",water),Map.of(),Map.of(),coordinate -> {
                if (++calls[0] > 2)
                    fail("Directory must be frozen before writing any batch");
                return directoryFor(a,b).resolve(coordinate);
            },1,2);
        assertEquals(2,calls[0]);
        assertEquals(2,drained.batches().size());
    }

    @Test void twoFrontiersOwnedByOneRegionConsumeOnlyOneBudgetSlot()
            throws IOException {
        var water = waterSender("shared",Map.of(TO_A,7.0,TO_B,8.0));
        var both = receiver("shared-receiver",A,B);
        var drained = HydrologyCoupledFrontierResolutionV2.deliverBatches(
            Map.of("sender",water),Map.of(),Map.of(),coordinate ->
                new HydrologyCoupledFrontierResolutionV2.Entry("both",both),1,1);
        assertEquals(1,drained.preflight().addedRegions().size());
        assertEquals(2,drained.batches().get(0).plan().resolvedFrontiers());
        assertEquals(15.0,drained.batches().get(0).delivery()
            .after().water().report().acknowledged().doubleValue());
    }

    @Test void conflictingDeferredPhysicalOwnerFailsBeforeFirstBatch()
            throws IOException {
        var water = waterSender("overlap",Map.of(TO_A,7.0,TO_B,8.0));
        var both = receiver("overlap-both",A,B);
        var b = receiver("overlap-b",B);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatches(
                Map.of("sender",water),Map.of(),Map.of(),directoryFor(both,b),1,2));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water)
            .acknowledged().isEmpty());
    }
    @Test void directoryCannotAppendSedimentDuringPreflightBeforeWaterWrites()
            throws IOException {
        var water = waterSender("fenced-water",Map.of(TO_A,7.0));
        var sediment = sedimentSender("fenced-sediment",Map.of());
        var receiver = receiver("fenced-a",A);
        var extra = new HydrologySedimentReceiptInboxV2.Receipt(
            0,1,at(7,4),B);
        var invoked = new boolean[1];
        assertThrows(IOException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverAll(
                Map.of("w",water),Map.of("s",sediment),Map.of(),coordinate -> {
                    if (!invoked[0]) {
                        invoked[0] = true;
                        var expected = HydrologyCheckpointStoreV2.load(sediment);
                        HydrologySedimentSenderOutboxV2.appendDurable(
                            sediment,expected,
                            new HydrologySedimentFrontierTransfersV2.Outgoing(
                                Map.of(extra,3.0),3.0));
                    }
                    return new HydrologyCoupledFrontierResolutionV2.Entry(
                        "fenced-a",receiver);
                },1));
        assertTrue(invoked[0]);
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water)
            .acknowledged().isEmpty());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(
            receiver.waterCheckpoint()).inbox().receipts().isEmpty());
        assertEquals(1,HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(sediment)).receipts().size());
    }

    @Test void directoryCannotChangeWaterInboxDuringPreflight()
            throws IOException {
        var water = waterSender("fenced-inbox",Map.of(TO_A,7.0));
        var receiver = receiver("fenced-inbox-a",A);
        assertThrows(IOException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatch(
                Map.of("w",water),Map.of(),Map.of(),coordinate -> {
                    var expected = HydrologyMixedResolutionCheckpointStoreV2.load(water);
                    HydrologyMixedResolutionCheckpointStoreV2.receive(
                        water,expected,new HydrologyMixedResolutionEpochV2.Receipt(
                            1,at(11,4),at(12,4)),2.0);
                    return new HydrologyCoupledFrontierResolutionV2.Entry(
                        "fenced-inbox-a",receiver);
                },1));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water)
            .acknowledged().isEmpty());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(
            receiver.waterCheckpoint()).inbox().receipts().isEmpty());
    }

    @Test void senderFenceAllowsOnlyLegitimateAcknowledgmentGrowth()
            throws IOException {
        var water = waterSender("fenced-replay",Map.of(TO_A,7.0));
        var sedimentReceipt = new HydrologySedimentReceiptInboxV2.Receipt(
            0,1,at(4,4),A);
        var sediment = sedimentSender("fenced-replay",Map.of(sedimentReceipt,3.0));
        var receiver = receiver("fenced-replay-a",A);
        var ws = Map.of("w",water);
        var ss = Map.of("s",sediment);
        var fence = HydrologyCoupledSenderFenceV2.capture(ws,ss);
        assertEquals(1,fence.report().waterReceipts());
        assertEquals(1,fence.report().sedimentReceipts());
        var result = HydrologyCoupledFrontierResolutionV2.deliverAll(
            ws,ss,Map.of(),coordinate ->
                new HydrologyCoupledFrontierResolutionV2.Entry(
                    "fenced-replay-a",receiver),1);
        assertTrue(result.plan().complete());
        assertDoesNotThrow(() -> fence.verify(ws,ss));
    }

    @Test void multiBatchDrainRejectsProducerMutationDuringDirectoryPreflight()
            throws IOException {
        var water = waterSender("fenced-batches",Map.of(TO_A,7.0,TO_B,8.0));
        var sediment = sedimentSender("fenced-batches",Map.of());
        var a = receiver("fenced-batches-a",A);
        var b = receiver("fenced-batches-b",B);
        var extra = new HydrologySedimentReceiptInboxV2.Receipt(
            0,1,at(9,4),at(10,4));
        var invoked = new boolean[1];
        assertThrows(IOException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverBatches(
                Map.of("w",water),Map.of("s",sediment),Map.of(),coordinate -> {
                    if (!invoked[0]) {
                        invoked[0] = true;
                        var expected = HydrologyCheckpointStoreV2.load(sediment);
                        HydrologySedimentSenderOutboxV2.appendDurable(
                            sediment,expected,
                            new HydrologySedimentFrontierTransfersV2.Outgoing(
                                Map.of(extra,5.0),5.0));
                    }
                    return coordinate.equals(physical(A))
                        ? new HydrologyCoupledFrontierResolutionV2.Entry("a",a)
                        : new HydrologyCoupledFrontierResolutionV2.Entry("b",b);
                },1,2));
        assertTrue(invoked[0]);
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water)
            .acknowledged().isEmpty());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(
            a.waterCheckpoint()).inbox().receipts().isEmpty());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(
            b.waterCheckpoint()).inbox().receipts().isEmpty());
    }

    @Test void sedimentEpochAdvanceWithoutAcknowledgmentCannotBypassSenderFence()
            throws IOException {
        var water = waterSender("epoch-fence",Map.of(TO_A,7.0));
        var sediment = sedimentSender("epoch-fence",Map.of());
        var receiver = receiver("epoch-fence-a",A);
        var initial = HydrologyCheckpointStoreV2.load(sediment);
        assertThrows(IOException.class,() ->
            HydrologyCoupledFrontierResolutionV2.deliverAll(
                Map.of("w",water),Map.of("s",sediment),Map.of(),coordinate -> {
                    HydrologyCheckpointStoreV2.saveNext(sediment,initial,
                        new HydrologyCheckpointV2.Checkpoint(
                            initial.epoch()+1,initial.worldState(),initial.inboxState()));
                    return new HydrologyCoupledFrontierResolutionV2.Entry(
                        "epoch-fence-a",receiver);
                },1));
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(water)
            .acknowledged().isEmpty());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(
            receiver.waterCheckpoint()).inbox().receipts().isEmpty());
    }

    @Test void senderFenceRejectsSedimentEpochDriftWithIdenticalJournal()
            throws IOException {
        var sediment = sedimentSender("drift",Map.of());
        var paths = Map.of("s",sediment);
        var fence = HydrologyCoupledSenderFenceV2.capture(Map.of(),paths);
        var before = HydrologyCheckpointStoreV2.load(sediment);
        HydrologyCheckpointStoreV2.saveNext(sediment,before,
            new HydrologyCheckpointV2.Checkpoint(
                before.epoch()+1,before.worldState(),before.inboxState()));
        assertThrows(IOException.class,() -> fence.verify(Map.of(),paths));
    }

    @Test void senderFenceRejectsAliasedCheckpointPathsBeforeDelivery()
            throws IOException {
        var sediment = sedimentSender("alias",Map.of());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledSenderFenceV2.capture(Map.of(),
                Map.of("first",sediment,"second",sediment)));
        var water = waterSender("alias",Map.of(TO_A,7.0));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledSenderFenceV2.capture(
                Map.of("first",water,"second",water),Map.of()));
    }

}
