package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Large-batch and fail-closed sediment receipt recovery acceptance. */
class HydrologySedimentBatchScaleV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(
            int level, int divisions, long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(level,divisions,x,y);
    }

    private static HydrologySedimentReceiptInboxV2.Receipt edge(
            long source, long arrival,
            GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(
            source,arrival,from,to);
    }

    private static HydrologyCheckpointV2.Checkpoint initial(Path path)
            throws IOException {
        var checkpoint = HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld("world"),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        return checkpoint;
    }

    @Test void thousandsOfDistinctEpochsPersistAndReplayWithoutDuplication()
            throws IOException {
        var a = at(1,2,0,2);
        var b = at(1,2,1,2);
        var forkTarget = at(1,2,0,3);
        var batch = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        for (int i = 2047; i >= 0; i--)
            batch.put(edge(i,i+1,a,b),1.0);
        Path path = directory.resolve("large-sediment.chk");
        var before = initial(path);
        var committed = HydrologySedimentCheckpointV2.receiveBatch(path,before,batch);
        var restored = HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(path).worldState()).sediment();
        assertEquals(2048,restored.receipts().size());
        assertEquals(restored,HydrologySedimentReceiptCodecV2.decode(
            HydrologySedimentReceiptCodecV2.encode(restored)));
        assertEquals(committed,HydrologySedimentCheckpointV2.receiveBatch(
            path,committed,batch));
        var forged = new HashMap<>(batch);
        forged.put(edge(1024,1026,a,forkTarget),2.0);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySedimentCheckpointV2.receiveBatch(path,committed,forged));
        assertEquals(committed,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void crossResolutionPhysicalSourceForkRejectsWholeBatch()
            throws IOException {
        var coarse = edge(0,2,at(1,2,0,2),at(1,2,1,2));
        var alias = edge(0,3,at(2,2,0,4),at(2,2,1,4));
        Path path = directory.resolve("alias-sediment.chk");
        var before = initial(path);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySedimentCheckpointV2.receiveBatch(path,before,
                Map.of(coarse,4.0,alias,5.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void rejectedBatchLeavesDeliveredHistoryAndInputUnchanged() {
        var a = at(1,2,0,2);
        var b = at(1,2,1,2);
        var d = at(1,2,1,3);
        var existing = edge(0,1,a,b);
        var previous = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),existing,2);
        var delivered = HydrologySedimentReceiptInboxV2.claimAvailable(
            previous,1,List.of(b)).snapshot();
        var future = edge(2,3,d,b);
        var late = edge(0,1,d,b);
        assertThrows(IllegalStateException.class,() ->
            HydrologySedimentReceiptInboxV2.receiveBatch(delivered,
                Map.of(future,3.0,late,1.0)));
        assertEquals(Set.of(existing),delivered.delivered());
        assertEquals(1,delivered.receipts().size());
        assertEquals(1,delivered.lastClaimedEpoch());
        assertSame(delivered,HydrologySedimentReceiptInboxV2.receiveBatch(
            delivered,Map.of(existing,2.0)));
        var accepted = HydrologySedimentReceiptInboxV2.receiveBatch(
            delivered,Map.of(existing,2.0,future,3.0));
        assertEquals(2,accepted.receipts().size());
        assertEquals(Set.of(existing),accepted.delivered());
    }

    @Test void batchOrderDoesNotAffectCanonicalSedimentCheckpoint()
            throws IOException {
        var a = at(1,2,0,2);
        var b = at(1,2,1,2);
        var first = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        var reverse = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        for (int i = 0; i < 256; i++)
            first.put(edge(i,i+1,a,b),i+1.0);
        for (int i = 255; i >= 0; i--)
            reverse.put(edge(i,i+1,a,b),i+1.0);
        Path left = directory.resolve("left.chk");
        Path right = directory.resolve("right.chk");
        var l = HydrologySedimentCheckpointV2.receiveBatch(left,initial(left),first);
        var r = HydrologySedimentCheckpointV2.receiveBatch(right,initial(right),reverse);
        assertEquals(l,r);
        assertArrayEquals(java.nio.file.Files.readAllBytes(left),
            java.nio.file.Files.readAllBytes(right));
    }
}
