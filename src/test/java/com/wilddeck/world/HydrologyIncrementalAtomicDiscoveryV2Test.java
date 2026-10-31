package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyIncrementalAtomicDiscoveryV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }

    private static final GlobalHydrologyAddressV2.Sample OUTLET = at(0, 2);
    private static final HydrologyGlobalSlopeRouterV2.Elevations TERRAIN =
        a -> a.equals(OUTLET) ? 0 : 10;

    private static HydrologyWorldRunnerV2 initial(Path path) throws IOException {
        var flood = HydrologyIncrementalPriorityFloodV2.start(Set.of(OUTLET), TERRAIN);
        flood.advance(1);
        return HydrologyWorldRunnerV2.open(path, 0,
            HydrologyIncrementalAtomicEpochV2.encode(
                new HydrologyIncrementalAtomicEpochV2.Bound(
                    "original", flood.checkpoint("fixed"))));
    }

    private static List<HydrologyGlobalSlopeRouterV2.Source> water(
            HydrologyIncrementalPriorityFloodV2.Snapshot snapshot, double amount) {
        return snapshot.settled().keySet().stream()
            .map(a -> new HydrologyGlobalSlopeRouterV2.Source(
                a, a.equals(at(4, 2)) ? amount : 0)).toList();
    }

    @Test void boundedPendingDiscoveryRestartsAndActivatesExactlyOnce()
            throws IOException {
        var file = directory.resolve("discover.bin");
        var runner = initial(file);
        var receipt = new HydrologyTransferLedgerV2.Key(
            1, at(3, 2), at(4, 2));
        assertTrue(runner.receive(receipt, 4));
        var first = HydrologyIncrementalAtomicEpochV2.discoverPending(
            runner, "fixed", TERRAIN, 1);
        assertFalse(first.ready());
        assertEquals(Set.of(at(4, 2)), first.unresolved());
        assertEquals(0, runner.epoch());
        assertEquals(4, runner.receiptBudget().pendingVolume(), 1e-12);
        var firstCount = HydrologyIncrementalAtomicEpochV2.decode(
            runner.worldState()).flood().settledCount();
        assertEquals(2, firstCount);
        assertEquals("original", HydrologyIncrementalAtomicEpochV2.decode(
            runner.worldState()).world());

        runner = HydrologyWorldRunnerV2.open(file, 0, "ignored");
        assertEquals(firstCount, HydrologyIncrementalAtomicEpochV2.resumeBound(
            file, "fixed", TERRAIN).snapshot().settled().size());
        boolean advanced = false;
        for (int i = 0; i < 40; i++) {
            var result = HydrologyIncrementalAtomicEpochV2.discoverAndAdvance(
                runner, "activated", "fixed", TERRAIN, 2,
                snapshot -> water(snapshot, 1));
            if (result.advanced()) {
                assertTrue(result.discovery().ready());
                assertEquals(5, result.committed().committed().flow()
                    .terminalOutflow().get(OUTLET), 1e-12);
                advanced = true;
                break;
            }
            assertFalse(result.discovery().ready());
            assertEquals(0, runner.epoch());
            assertEquals(4, runner.receiptBudget().pendingVolume(), 1e-12);
            runner = HydrologyWorldRunnerV2.open(file, 0, "ignored");
        }
        assertTrue(advanced, "bounded flood never reached due receipt destination");
        assertEquals(1, runner.epoch());
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(Set.of(receipt), runner.inboxSnapshot().delivered());

        runner = HydrologyWorldRunnerV2.open(file, 0, "ignored");
        var dry = HydrologyIncrementalAtomicEpochV2.discoverAndAdvance(
            runner, "next", "fixed", TERRAIN, 2,
            snapshot -> water(snapshot, 0));
        assertTrue(dry.advanced());
        assertEquals(0, dry.committed().committed().flow()
            .terminalOutflow().get(OUTLET), 1e-12);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(2, runner.epoch());
    }

    @Test void invalidDueReceiptAndWrongTerrainDoNotModifyCheckpoint()
            throws IOException {
        var file = directory.resolve("invalid.bin");
        var runner = initial(file);
        var alias = new HydrologyTransferLedgerV2.Key(
            1, at(8, 2), at(7, 2));
        runner.receive(alias, 7);
        var before = HydrologyCheckpointStoreV2.load(file);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.discoverPending(
                runner, "fixed", TERRAIN, 2));
        assertEquals(before, HydrologyCheckpointStoreV2.load(file));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.discoverPending(
                runner, "wrong-revision", TERRAIN, 2));
        assertEquals(before, HydrologyCheckpointStoreV2.load(file));
    }

    @Test void reentrantReceiptDuringTerrainReplayCannotPublishStaleDiscovery()
            throws IOException {
        var file = directory.resolve("racing.bin");
        var runner = initial(file);
        var due = new HydrologyTransferLedgerV2.Key(1, at(3, 2), at(4, 2));
        var other = new HydrologyTransferLedgerV2.Key(1, at(5, 2), at(6, 2));
        runner.receive(due, 4);
        var original = HydrologyIncrementalAtomicEpochV2.decode(
            runner.worldState()).flood();
        var fired = new AtomicBoolean(false);
        HydrologyGlobalSlopeRouterV2.Elevations racing = a -> {
            if (a.equals(at(1, 2)) && fired.compareAndSet(false, true)) {
                try { runner.receive(other, 3); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            }
            return TERRAIN.at(a);
        };
        assertThrows(IOException.class, () ->
            HydrologyIncrementalAtomicEpochV2.discoverPending(
                runner, "fixed", racing, 2));
        assertTrue(fired.get());
        var reopened = HydrologyWorldRunnerV2.open(file, 0, "ignored");
        assertEquals(original, HydrologyIncrementalAtomicEpochV2.decode(
            reopened.worldState()).flood());
        assertEquals(0, reopened.epoch());
        assertEquals(7, reopened.receiptBudget().pendingVolume(), 1e-12);
        assertTrue(reopened.inboxSnapshot().delivered().isEmpty());
    }

    @Test void duplicateWaterOwnershipRejectsActivationWithoutConsumingReceipt()
            throws IOException {
        var file = directory.resolve("duplicate.bin");
        var runner = initial(file);
        var key = new HydrologyTransferLedgerV2.Key(1, at(3, 2), at(4, 2));
        runner.receive(key, 4);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.discoverAndAdvance(
                runner, "bad", "fixed", TERRAIN, 100,
                snapshot -> {
                    var sources = new ArrayList<>(water(snapshot, 1));
                    sources.add(sources.get(0));
                    return sources;
                }));
        assertEquals(0, runner.epoch());
        assertEquals(4, runner.receiptBudget().pendingVolume(), 1e-12);
        assertTrue(runner.inboxSnapshot().delivered().isEmpty());
        assertEquals("original", HydrologyIncrementalAtomicEpochV2.decode(
            runner.worldState()).world());
        var good = HydrologyIncrementalAtomicEpochV2.discoverAndAdvance(
            runner, "good", "fixed", TERRAIN, 1,
            snapshot -> water(snapshot, 1));
        assertTrue(good.advanced());
        assertEquals(5, good.committed().committed().flow()
            .terminalOutflow().get(OUTLET), 1e-12);
    }

    @Test void sameEpochFloodStoreRejectsWorldAndInboxTampering()
            throws IOException {
        var file = directory.resolve("tamper.bin");
        var runner = initial(file);
        var original = HydrologyCheckpointStoreV2.load(file);
        var flood = HydrologyIncrementalAtomicEpochV2.decode(
            original.worldState()).flood();
        var session = HydrologyIncrementalPriorityFloodV2.resume(
            flood, "fixed", TERRAIN);
        session.advance(2);
        var updated = HydrologyIncrementalAtomicEpochV2.encode(
            new HydrologyIncrementalAtomicEpochV2.Bound(
                "original", session.checkpoint("fixed")));
        var tamperedWorld = HydrologyIncrementalAtomicEpochV2.encode(
            new HydrologyIncrementalAtomicEpochV2.Bound(
                "changed", session.checkpoint("fixed")));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyCheckpointStoreV2.saveFloodDiscovery(
                file, original, new HydrologyCheckpointV2.Checkpoint(
                    original.epoch(), tamperedWorld, original.inboxState())));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyCheckpointStoreV2.saveFloodDiscovery(
                file, original, new HydrologyCheckpointV2.Checkpoint(
                    original.epoch(), updated, "changed-inbox")));
        assertEquals(original, HydrologyCheckpointStoreV2.load(file));
        assertEquals(0, runner.epoch());
    }
}
