package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Durable local coupling of incoming water and sediment receipt histories. */
class HydrologyCoupledReceiptBatchV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static HydrologyTransferLedgerV2.Key water(long epoch,long x,long y,
                                                        long tx,long ty) {
        return new HydrologyTransferLedgerV2.Key(epoch,at(x,y),at(tx,ty));
    }
    private static HydrologySedimentReceiptInboxV2.Receipt sediment(
            long source,long arrival,long x,long y,long tx,long ty) {
        return new HydrologySedimentReceiptInboxV2.Receipt(
            source,arrival,at(x,y),at(tx,ty));
    }
    private static HydrologyCheckpointV2.Checkpoint initial(Path path)
            throws IOException {
        var checkpoint = HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld("world"),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        return checkpoint;
    }

    @Test void persistsBothKindsInOneCheckpointAndReplaysAfterRestart()
            throws IOException {
        var path = directory.resolve("coupled.chk");
        var before = initial(path);
        var w = water(1,4,4,5,4);
        var s = sediment(0,1,4,4,5,4);
        var result = HydrologyCoupledReceiptBatchV2.receive(
            path,before,Map.of(w,7.0),Map.of(s,3.0));
        assertEquals(1,result.waterAdded());
        assertEquals(1,result.sedimentAdded());
        assertEquals(result.checkpoint(),HydrologyCheckpointStoreV2.load(path));
        assertEquals(7.0,HydrologyInboxCodecV2.decode(
            result.checkpoint().inboxState()).receipts().get(w),0);
        assertEquals(3.0,HydrologySedimentCheckpointV2.decode(
            result.checkpoint().worldState()).sediment().receipts().get(s),0);
        byte[] bytes = Files.readAllBytes(path);
        var replay = HydrologyCoupledReceiptBatchV2.receive(path,
            HydrologyCheckpointStoreV2.load(path),Map.of(w,7.0),Map.of(s,3.0));
        assertEquals(0,replay.waterAdded());
        assertEquals(0,replay.sedimentAdded());
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void invalidSedimentSourceForkRollsBackValidWater()
            throws IOException {
        var path = directory.resolve("sediment-fork.chk");
        var before = initial(path);
        var w = water(1,4,4,5,4);
        var a = sediment(0,1,4,4,5,4);
        var b = sediment(0,2,4,4,4,5);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(w,5.0),Map.of(a,2.0,b,3.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void invalidWaterSourceForkRollsBackValidSediment()
            throws IOException {
        var path = directory.resolve("water-fork.chk");
        var before = initial(path);
        var a = water(1,4,4,5,4);
        var b = water(1,4,4,4,5);
        var s = sediment(0,1,4,4,5,4);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(a,5.0,b,3.0),Map.of(s,2.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void staleWriterCannotEraseEitherReceiptHistory() throws IOException {
        var path = directory.resolve("stale.chk");
        var before = initial(path);
        var first = HydrologyCoupledReceiptBatchV2.receive(path,before,
            Map.of(water(1,4,4,5,4),2.0),
            Map.of(sediment(0,1,4,4,5,4),3.0));
        assertThrows(IOException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(water(2,6,4,5,4),4.0),
                Map.of(sediment(1,2,6,4,5,4),5.0)));
        assertEquals(first.checkpoint(),HydrologyCheckpointStoreV2.load(path));
        assertThrows(IOException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,Map.of(),Map.of()));
    }

    @Test void rejectsNewLateReceiptsWithoutPartialPersistence() throws IOException {
        var path = directory.resolve("late.chk");
        var before = initial(path);
        var validWater = water(1,4,4,5,4);
        var validSediment = sediment(0,1,4,4,5,4);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(water(0,6,4,5,4),2.0),Map.of(validSediment,3.0)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(validWater,2.0),
                Map.of(sediment(0,0,4,4,5,4),3.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void independentInsertionOrderProducesIdenticalDurableBytes()
            throws IOException {
        var first = new LinkedHashMap<HydrologyTransferLedgerV2.Key,Double>();
        var reverse = new LinkedHashMap<HydrologyTransferLedgerV2.Key,Double>();
        var sFirst = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        var sReverse = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        for (int i=0;i<64;i++) {
            first.put(water(i+1,4,4,5,4),i+1.0);
            sFirst.put(sediment(i,i+1,4,4,5,4),i+1.0);
        }
        for (int i=63;i>=0;i--) {
            reverse.put(water(i+1,4,4,5,4),i+1.0);
            sReverse.put(sediment(i,i+1,4,4,5,4),i+1.0);
        }
        var a = directory.resolve("order-a.chk");
        var b = directory.resolve("order-b.chk");
        HydrologyCoupledReceiptBatchV2.receive(a,initial(a),first,sFirst);
        HydrologyCoupledReceiptBatchV2.receive(b,initial(b),reverse,sReverse);
        assertArrayEquals(Files.readAllBytes(a),Files.readAllBytes(b));
    }

    @Test void conflictingHistoricalReplayDoesNotModifyStoredCheckpoint()
            throws IOException {
        var path = directory.resolve("conflict.chk");
        var before = initial(path);
        var w = water(1,4,4,5,4);
        var s = sediment(0,1,4,4,5,4);
        var committed = HydrologyCoupledReceiptBatchV2.receive(
            path,before,Map.of(w,4.0),Map.of(s,3.0)).checkpoint();
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,committed,
                Map.of(w,5.0),Map.of(s,3.0)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,committed,
                Map.of(w,4.0),Map.of(s,5.0)));
        assertEquals(committed,HydrologyCheckpointStoreV2.load(path));
    }
}
