package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Atomic local water batch ingress, including cross-region source fencing. */
class HydrologyWaterBatchReceivingV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(
            int level, int divisions, long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(level,divisions,x,y);
    }
    private static HydrologyTransferLedgerV2.Key edge(
            long epoch, GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologyTransferLedgerV2.Key(epoch,from,to);
    }

    @Test void confluenceBatchIsDurableIdempotentAndRestartSafe()
            throws IOException {
        var mouth = at(2,4,5,4);
        var a = edge(1,at(2,4,4,4),mouth);
        var b = edge(1,at(2,4,5,3),mouth);
        var input = Map.of(a,2.0,b,3.0);
        Path path = directory.resolve("water-batch.chk");
        var runner = HydrologyWorldRunnerV2.open(path,0,"world");
        assertEquals(2,runner.receiveBatch(input));
        assertEquals(0,runner.receiveBatch(input));
        var checkpoint = HydrologyCheckpointStoreV2.load(path);
        assertEquals(0,checkpoint.epoch());
        assertEquals(2,HydrologyInboxCodecV2.decode(
            checkpoint.inboxState()).receipts().size());
        var restored = HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(0,restored.receiveBatch(input));
        assertEquals(5,HydrologyEpochInboxV2.restore(
            restored.inboxSnapshot()).claimAvailable(1,Set.of(mouth))
                .get(mouth),1e-12);
    }

    @Test void invalidLateWaterDoesNotPartiallyPersistEarlierReceipts()
            throws IOException {
        Path path = directory.resolve("invalid-water.chk");
        var runner = HydrologyWorldRunnerV2.open(path,0,"world");
        var before = HydrologyCheckpointStoreV2.load(path);
        var valid = edge(1,at(2,4,4,4),at(2,4,5,4));
        var closed = edge(0,at(2,4,6,4),at(2,4,5,4));
        assertThrows(IllegalArgumentException.class,() ->
            runner.receiveBatch(Map.of(valid,2.0,closed,3.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
        assertTrue(runner.inboxSnapshot().receipts().isEmpty());
    }

    @Test void crossResolutionSourceForkFailsWholeBatchAndKeepsReplay()
            throws IOException {
        Path path = directory.resolve("source-fork.chk");
        var runner = HydrologyWorldRunnerV2.open(path,0,"world");
        var fine = edge(1,at(2,4,4,4),at(2,4,5,4));
        var coarse = edge(1,at(1,4,2,2),at(1,4,3,2));
        assertThrows(IllegalArgumentException.class,() ->
            runner.receiveBatch(Map.of(fine,2.0,coarse,3.0)));
        assertTrue(runner.inboxSnapshot().receipts().isEmpty());
        assertEquals(1,runner.receiveBatch(Map.of(fine,2.0)));
        var persisted = HydrologyCheckpointStoreV2.load(path);
        assertThrows(IllegalArgumentException.class,() ->
            runner.receiveBatch(Map.of(fine,2.0,coarse,3.0)));
        assertEquals(persisted,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void staleRunnerCannotEraseAnotherWritersBatch() throws IOException {
        Path path = directory.resolve("stale-water.chk");
        var first = HydrologyWorldRunnerV2.open(path,0,"world");
        var stale = HydrologyWorldRunnerV2.open(path,0,"world");
        var a = edge(1,at(2,4,4,4),at(2,4,5,4));
        var b = edge(1,at(2,4,6,4),at(2,4,5,4));
        assertEquals(1,first.receiveBatch(Map.of(a,2.0)));
        var saved = HydrologyCheckpointStoreV2.load(path);
        assertThrows(IOException.class,() ->
            stale.receiveBatch(Map.of(b,3.0)));
        assertEquals(saved,HydrologyCheckpointStoreV2.load(path));
        assertEquals(1,first.receiveBatch(Map.of(b,3.0)));
        assertEquals(2,first.inboxSnapshot().receipts().size());
    }

    @Test void batchRejectsConflictingReplayAndPreservesInputSnapshot() {
        var inbox = new HydrologyEpochInboxV2();
        var a = edge(1,at(2,4,4,4),at(2,4,5,4));
        var b = edge(1,at(2,4,6,4),at(2,4,5,4));
        assertEquals(1,inbox.receiveBatch(Map.of(a,2.0)));
        var before = inbox.snapshot();
        assertThrows(IllegalArgumentException.class,() ->
            inbox.receiveBatch(Map.of(a,4.0,b,3.0)));
        assertEquals(before,inbox.snapshot());
        assertEquals(1,inbox.receiveBatch(Map.of(a,2.0,b,3.0)));
        assertEquals(0,inbox.receiveBatch(Map.of(a,2.0,b,3.0)));
        inbox.claimAvailable(1,Set.of(at(2,4,5,4)));
        assertThrows(IllegalStateException.class,() ->
            inbox.receiveBatch(Map.of(a,2.0)));
    }
}
