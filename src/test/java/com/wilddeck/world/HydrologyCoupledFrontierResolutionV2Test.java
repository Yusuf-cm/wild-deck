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
}
