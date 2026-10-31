package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Local checkpoint writer serialization; not a distributed lock test. */
class HydrologyMixedResolutionCheckpointLockV2Test {
    @TempDir Path temp;

    @Test void concurrentLocalWritersCannotLoseUpdates() throws Exception {
        Path file = temp.resolve("counter.chk");
        Files.writeString(file, "0");
        var pool = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 96; i++)
                futures.add(pool.submit(() -> {
                    start.await();
                    HydrologyCheckpointFileLockV2.withLock(file, () -> {
                        int old = Integer.parseInt(Files.readString(file));
                        Files.writeString(file, Integer.toString(old + 1));
                        return null;
                    });
                    return null;
                }));
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertEquals("96", Files.readString(file));
            assertTrue(Files.exists(temp.resolve(".counter.chk.hydrology.lock")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void twoReceiversWithSameExpectedSnapshotCannotBothCommit() throws Exception {
        Path file = temp.resolve("region.chk");
        var initial = new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            0, "world", new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()));
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file, initial);
        var from1 = new GlobalHydrologyAddressV2.Sample(2, 4, 4, 4);
        var from2 = new GlobalHydrologyAddressV2.Sample(2, 4, 6, 4);
        var to = new GlobalHydrologyAddressV2.Sample(2, 4, 5, 4);
        var r1 = new HydrologyMixedResolutionEpochV2.Receipt(0, from1, to);
        var r2 = new HydrologyMixedResolutionEpochV2.Receipt(0, from2, to);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> {
                gate.await();
                return HydrologyMixedResolutionCheckpointStoreV2.receive(file, initial, r1, 3);
            });
            var b = pool.submit(() -> {
                gate.await();
                return HydrologyMixedResolutionCheckpointStoreV2.receive(file, initial, r2, 5);
            });
            gate.countDown();
            int succeeded = 0, stale = 0;
            for (var f : List.of(a, b)) {
                try { f.get(20, TimeUnit.SECONDS); succeeded++; }
                catch (ExecutionException ex) {
                    assertInstanceOf(IOException.class, ex.getCause());
                    stale++;
                }
            }
            assertEquals(1, succeeded);
            assertEquals(1, stale);
            var saved = HydrologyMixedResolutionCheckpointStoreV2.load(file);
            assertEquals(1, saved.inbox().receipts().size());
            assertEquals(1, saved.inbox().receipts().values().stream()
                .filter(v -> v == 3.0 || v == 5.0).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void checkpointSymlinkWritesAreRejectedRatherThanReplacingAlias() throws Exception {
        Path file = temp.resolve("region.chk");
        var initial = new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(
            0, "world", new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()));
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file, initial);
        Path alias = temp.resolve("alias.chk");
        Files.createSymbolicLink(alias, file);
        var receipt = new HydrologyMixedResolutionEpochV2.Receipt(0,
            new GlobalHydrologyAddressV2.Sample(2, 4, 4, 4),
            new GlobalHydrologyAddressV2.Sample(2, 4, 5, 4));
        assertThrows(IOException.class, () ->
            HydrologyMixedResolutionCheckpointStoreV2.receive(alias, initial, receipt, 2));
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(initial, HydrologyMixedResolutionCheckpointStoreV2.load(file));
    }

    @Test void sidecarLockSurvivesCheckpointAtomicReplacement() throws Exception {
        Path file = temp.resolve("replaced.chk");
        Files.writeString(file, "before");
        HydrologyCheckpointFileLockV2.withLock(file, () -> {
            Path replacement = temp.resolve("replacement.tmp");
            Files.writeString(replacement, "after");
            Files.move(replacement, file, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            return null;
        });
        assertEquals("after", HydrologyCheckpointFileLockV2.withLock(file,
            () -> Files.readString(file)));
        assertTrue(Files.exists(temp.resolve(".replaced.chk.hydrology.lock")));
    }
    @Test void hardLinkedCheckpointWritersAreRejectedOnUnix() throws Exception {
        Path file = temp.resolve("original.chk");
        Files.writeString(file, "before");
        if (!Files.getFileStore(file).supportsFileAttributeView("unix")) return;
        Path alias = temp.resolve("hardlink.chk");
        Files.createLink(alias, file);
        assertThrows(IOException.class, () ->
            HydrologyCheckpointFileLockV2.withLock(alias, () -> null));
        assertEquals("before", Files.readString(file));
    }

}
