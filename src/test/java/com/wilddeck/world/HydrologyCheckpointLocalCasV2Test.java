package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for cooperative local-file CAS. These do not establish
 * distributed atomicity or protection against noncooperating writers.
 */
class HydrologyCheckpointLocalCasV2Test {
    @TempDir Path directory;

    private static HydrologyCheckpointV2.Checkpoint checkpoint(long epoch, String state) {
        return HydrologyCheckpointV2.capture(epoch, state, new HydrologyEpochInboxV2());
    }

    @Test void concurrentWritersFromSameSnapshotCannotBothCommit() throws Exception {
        Path file = directory.resolve("racing-water.chk");
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 12; round++) {
                var initial = checkpoint(round * 2L, "initial-" + round);
                if (round == 0) HydrologyCheckpointStoreV2.save(file, initial);
                else HydrologyCheckpointStoreV2.save(file, initial);
                var first = new HydrologyCheckpointV2.Checkpoint(
                    initial.epoch() + 1, "writer-a", initial.inboxState());
                var second = new HydrologyCheckpointV2.Checkpoint(
                    initial.epoch() + 1, "writer-b", initial.inboxState());
                var gate = new CountDownLatch(1);
                var tasks = List.of(first, second);
                var futures = new ArrayList<Future<Boolean>>();
                for (var next : tasks)
                    futures.add(pool.submit(() -> {
                        gate.await();
                        try {
                            HydrologyCheckpointStoreV2.saveNext(file, initial, next);
                            return true;
                        } catch (IOException expectedStale) {
                            return false;
                        }
                    }));
                gate.countDown();
                int successful = 0;
                for (var future : futures)
                    if (future.get(20, TimeUnit.SECONDS)) successful++;
                assertEquals(1, successful, "two stale writers both committed");
                var saved = HydrologyCheckpointStoreV2.load(file);
                assertEquals(initial.epoch() + 1, saved.epoch());
                assertTrue(saved.equals(first) || saved.equals(second));
                assertTrue(Files.exists(directory.resolve(".racing-water.chk.hydrology.lock")));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void concurrentSameEpochReceiptsRejectStaleAppendInsteadOfLosingWater()
            throws Exception {
        Path file = directory.resolve("incoming.chk");
        var initial = checkpoint(0, "unchanged");
        HydrologyCheckpointStoreV2.save(file, initial);
        var from = new GlobalHydrologyAddressV2.Sample(2,4,4,3);
        var a = new GlobalHydrologyAddressV2.Sample(2,4,5,3);
        var b = new GlobalHydrologyAddressV2.Sample(2,4,4,4);
        var keyA = new HydrologyTransferLedgerV2.Key(1,from,a);
        var keyB = new HydrologyTransferLedgerV2.Key(1,from,b);
        var inboxA = new HydrologyEpochInboxV2();
        var inboxB = new HydrologyEpochInboxV2();
        inboxA.receive(keyA, 3);
        inboxB.receive(keyB, 5);
        var updates = List.of(HydrologyCheckpointV2.capture(0,"unchanged",inboxA),
                              HydrologyCheckpointV2.capture(0,"unchanged",inboxB));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (var next : updates)
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        HydrologyCheckpointStoreV2.saveIncoming(file, next);
                        return true;
                    } catch (IOException expectedStale) {
                        return false;
                    }
                }));
            gate.countDown();
            int success = 0;
            for (var future : futures)
                if (future.get(20, TimeUnit.SECONDS)) success++;
            assertEquals(1, success);
            var saved = HydrologyCheckpointV2.restoreInbox(
                HydrologyCheckpointStoreV2.load(file)).snapshot().receipts();
            assertEquals(1, saved.size());
            assertTrue(saved.containsKey(keyA) || saved.containsKey(keyB));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void symlinkAndHardlinkCannotBypassCheckpointWriterFence() throws Exception {
        Path file = directory.resolve("canonical.chk");
        var initial = checkpoint(0, "original");
        HydrologyCheckpointStoreV2.save(file, initial);
        var next = checkpoint(1, "updated");
        Path alias = directory.resolve("alias.chk");
        Files.createSymbolicLink(alias, file);
        assertThrows(IOException.class, () ->
            HydrologyCheckpointStoreV2.saveNext(alias, initial, next));
        assertThrows(IOException.class, () ->
            HydrologyCheckpointStoreV2.save(alias, next));
        assertThrows(IOException.class, () ->
            HydrologyCheckpointStoreV2.saveIncoming(alias, initial));
        assertEquals(initial, HydrologyCheckpointStoreV2.load(file));
        if (Files.getFileStore(file).supportsFileAttributeView("unix")) {
            Path hardlink = directory.resolve("hardlink.chk");
            Files.createLink(hardlink, file);
            assertThrows(IOException.class, () ->
                HydrologyCheckpointStoreV2.saveNext(hardlink, initial, next));
            assertThrows(IOException.class, () ->
                HydrologyCheckpointStoreV2.save(file, next));
            assertEquals(initial, HydrologyCheckpointStoreV2.load(file));
        }
    }

    @Test void reversedFleetLockOrdersSerializeWithoutDeadlock() throws Exception {
        Path a = directory.resolve("a.chk");
        Path b = directory.resolve("b.chk");
        Files.writeString(a, "0");
        Files.writeString(b, "0");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (var order : List.of(List.of(a,b), List.of(b,a)))
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 32; i++)
                        HydrologyCheckpointFileLockV2.withLocks(order, () -> {
                            int old = Integer.parseInt(Files.readString(a));
                            Files.writeString(a, Integer.toString(old + 1));
                            return null;
                        });
                    return null;
                }));
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertEquals("64", Files.readString(a));
            assertTrue(Files.exists(directory.resolve(".a.chk.hydrology.lock")));
            assertTrue(Files.exists(directory.resolve(".b.chk.hydrology.lock")));
            assertThrows(IOException.class, () ->
                HydrologyCheckpointFileLockV2.withLocks(List.of(a,a), () -> null));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void fleetLockRejectsSameCheckpointThroughSymlinkedParent() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path file = real.resolve("water.chk");
        Files.writeString(file, "water");
        Path alias = directory.resolve("alias-parent");
        Files.createSymbolicLink(alias, real);
        assertThrows(IOException.class, () ->
            HydrologyCheckpointFileLockV2.withLocks(
                List.of(file,alias.resolve("water.chk")), () -> null));
        assertEquals("water", Files.readString(file));
    }
}
