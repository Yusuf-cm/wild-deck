package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentBatchReceivingV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(0,2),
        B=at(1,2), D=at(1,3), E=at(0,3);
    private static HydrologySedimentReceiptInboxV2.Receipt edge(
            long source,long arrival,GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(source,arrival,from,to);
    }
    private static HydrologyCheckpointV2.Checkpoint start(Path path) throws IOException {
        var initial=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld("world"),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,initial);
        return initial;
    }

    @Test void batchPersistsConfluenceInOneCheckpointAndRestarts() throws IOException {
        Path path=directory.resolve("batch.chk");
        var initial=start(path);
        var first=edge(0,2,A,B);
        var second=edge(0,2,D,B);
        var next=HydrologySedimentCheckpointV2.receiveBatch(path,initial,
            Map.of(first,2.0,second,3.0));
        assertEquals(0,next.epoch());
        assertEquals(next,HydrologyCheckpointStoreV2.load(path));
        var ledger=HydrologySedimentCheckpointV2.decode(next.worldState()).sediment();
        assertEquals(2,ledger.receipts().size());
        assertTrue(ledger.delivered().isEmpty());
        assertEquals(5,HydrologySedimentReceiptInboxV2.claimAvailable(
            ledger,2,List.of(B)).importedMass(),1e-12);
        assertEquals(next,HydrologySedimentCheckpointV2.receiveBatch(path,next,
            Map.of(second,3.0,first,2.0)));
    }

    @Test void invalidLaterReceiptCannotPersistEarlierBatchItems() throws IOException {
        Path path=directory.resolve("late.chk");
        var initial=start(path);
        var future=edge(0,2,A,B);
        var closed=edge(0,0,D,B);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCheckpointV2.receiveBatch(path,initial,
                Map.of(future,2.0,closed,3.0)));
        assertEquals(initial,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void physicalSenderForkWithinBatchFailsWithoutPartialCommit() throws IOException {
        Path path=directory.resolve("fork.chk");
        var initial=start(path);
        var first=edge(0,2,A,B);
        var fork=edge(0,3,A,E);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCheckpointV2.receiveBatch(path,initial,
                Map.of(first,2.0,fork,3.0)));
        assertEquals(initial,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void insertionOrderDoesNotChangeDurableCheckpointBytes() throws IOException {
        Path one=directory.resolve("one.chk"),two=directory.resolve("two.chk");
        var first=start(one);
        var second=start(two);
        var a=edge(0,2,A,B);
        var d=edge(0,2,D,B);
        var left=new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        left.put(a,1.0);
        left.put(d,4.0);
        var right=new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        right.put(d,4.0);
        right.put(a,1.0);
        var result1=HydrologySedimentCheckpointV2.receiveBatch(one,first,left);
        var result2=HydrologySedimentCheckpointV2.receiveBatch(two,second,right);
        assertEquals(result1,result2);
        assertArrayEquals(java.nio.file.Files.readAllBytes(one),
            java.nio.file.Files.readAllBytes(two));
    }

    @Test void staleBatchWriterCannotErasePreviouslyAppendedSediment() throws IOException {
        Path path=directory.resolve("stale.chk");
        var initial=start(path);
        var key=edge(0,2,A,B);
        var committed=HydrologySedimentCheckpointV2.receiveBatch(path,initial,
            Map.of(key,2.0));
        assertThrows(IOException.class,()->
            HydrologySedimentCheckpointV2.receiveBatch(path,initial,Map.of()));
        assertThrows(IOException.class,()->
            HydrologySedimentCheckpointV2.receiveBatch(path,initial,
                Map.of(edge(0,3,D,B),3.0)));
        assertEquals(committed,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void historicalExactReplayIsAllowedButNewLateReceiptIsRejected() throws IOException {
        Path path=directory.resolve("replay.chk");
        var initial=start(path);
        var key=edge(0,1,A,B);
        var pending=HydrologySedimentCheckpointV2.receiveBatch(path,initial,
            Map.of(key,2.0));
        var before=HydrologySedimentCheckpointV2.decode(pending.worldState());
        var claimed=HydrologySedimentReceiptInboxV2.claimAvailable(
            before.sediment(),1,List.of(B));
        var committed=new HydrologyCheckpointV2.Checkpoint(1,
            HydrologySedimentCheckpointV2.encode(before.world(),claimed.snapshot()),
            pending.inboxState());
        HydrologyCheckpointStoreV2.saveNext(path,pending,committed);
        assertEquals(committed,HydrologySedimentCheckpointV2.receiveBatch(
            path,committed,Map.of(key,2.0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentCheckpointV2.receiveBatch(path,committed,
                Map.of(edge(0,1,D,B),3.0)));
        assertEquals(committed,HydrologyCheckpointStoreV2.load(path));
    }
}
