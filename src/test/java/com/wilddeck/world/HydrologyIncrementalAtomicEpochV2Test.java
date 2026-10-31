package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyIncrementalAtomicEpochV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUTLET = at(0, 2);

    private static HydrologyIncrementalPriorityFloodV2.Session flood() {
        return HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), a -> a.equals(OUTLET) ? 0 : 10);
    }

    private static List<HydrologyGlobalSlopeRouterV2.Source> water(
            HydrologyIncrementalPriorityFloodV2.Snapshot snapshot,
            GlobalHydrologyAddressV2.Sample wet) {
        return snapshot.settled().keySet().stream()
            .map(a -> new HydrologyGlobalSlopeRouterV2.Source(
                a, a.equals(wet) ? 1 : 0)).toList();
    }

    @Test void codecPreservesOpaqueWorldAndReplayIdentity() {
        var session = flood();
        session.advance(2);
        var original = new HydrologyIncrementalAtomicEpochV2.Bound(
            "world\n🌊", session.checkpoint("terrain-v1"));
        var encoded = HydrologyIncrementalAtomicEpochV2.encode(original);
        assertEquals(original, HydrologyIncrementalAtomicEpochV2.decode(encoded));
        assertEquals(encoded, HydrologyIncrementalAtomicEpochV2.encode(
            HydrologyIncrementalAtomicEpochV2.decode(encoded)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.decode("not bound"));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.decode("WDI2:not-base64"));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.decode(encoded + "x"));
    }

    @Test void floodProgressAndReceiptConsumptionCommitInSameWorldFile()
            throws IOException {
        var session = flood();
        session.advance(1);
        var original = session.checkpoint("fixed");
        var world = directory.resolve("atomic-world.bin");
        var runner = HydrologyWorldRunnerV2.open(world, 0,
            HydrologyIncrementalAtomicEpochV2.encode(
                new HydrologyIncrementalAtomicEpochV2.Bound("initial", original)));
        var key = new HydrologyTransferLedgerV2.Key(1, at(3, 2), at(4, 2));
        assertTrue(runner.receive(key, 4));
        session.advance(100);
        var advanced = session.checkpoint("fixed");
        var snapshot = session.snapshot();
        var result = HydrologyIncrementalAtomicEpochV2.advance(
            runner, "next", "fixed", session, a -> a.equals(OUTLET) ? 0 : 10, water(snapshot, at(7, 2)));
        assertEquals(5, result.committed().flow().terminalOutflow().get(OUTLET), 1e-12);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(1, runner.epoch());
        assertEquals(new HydrologyIncrementalAtomicEpochV2.Bound("next", advanced),
            HydrologyIncrementalAtomicEpochV2.decode(runner.worldState()));

        var reopened = HydrologyWorldRunnerV2.open(world, 0, "ignored");
        assertEquals(Set.of(key), reopened.inboxSnapshot().delivered());
        assertEquals(advanced,
            HydrologyIncrementalAtomicEpochV2.decode(reopened.worldState()).flood());
        assertEquals(snapshot, HydrologyIncrementalAtomicEpochV2.resumeBound(
            world, "fixed", a -> a.equals(OUTLET) ? 0 : 10).snapshot());
        var zeros = water(snapshot, at(7, 2)).stream()
            .map(source -> new HydrologyGlobalSlopeRouterV2.Source(
                source.address(), 0)).toList();
        var dry = HydrologyIncrementalAtomicEpochV2.advance(
            reopened, "dry", "fixed", session, a -> a.equals(OUTLET) ? 0 : 10, zeros);
        assertEquals(0, dry.committed().flow().terminalOutflow().get(OUTLET), 1e-12);
        assertEquals(0, reopened.receiptBudget().pendingVolume(), 1e-12);
    }

    @Test void unsettledDueDestinationAndRegressedFloodLeaveWaterUntouched()
            throws IOException {
        var session = flood();
        session.advance(1);
        var world = directory.resolve("rejected-world.bin");
        var runner = HydrologyWorldRunnerV2.open(world, 0,
            HydrologyIncrementalAtomicEpochV2.encode(
                new HydrologyIncrementalAtomicEpochV2.Bound(
                    "initial", session.checkpoint("fixed"))));
        var key = new HydrologyTransferLedgerV2.Key(1, at(3, 2), at(4, 2));
        runner.receive(key, 4);
        var original = HydrologyCheckpointStoreV2.load(world);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.advance(
                runner, "wrong", "fixed", session, a -> a.equals(OUTLET) ? 0 : 10, water(session.snapshot(), OUTLET)));
        assertEquals(original, HydrologyCheckpointStoreV2.load(world));
        assertEquals(4, runner.receiptBudget().pendingVolume(), 1e-12);

        session.advance(100);
        var snapshot = session.snapshot();
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.advance(
                runner, "wrong", "other-terrain", session, a -> a.equals(OUTLET) ? 0 : 10, water(snapshot, OUTLET)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.advance(
                runner, "wrong", "fixed", session, a -> a.equals(OUTLET) ? 0 : 10, List.of()));
        assertEquals(original, HydrologyCheckpointStoreV2.load(world));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.advance(
                runner, "wrong", "fixed", session, a -> a.equals(OUTLET) ? 0 : 20,
                water(snapshot, OUTLET)));
        assertEquals(original, HydrologyCheckpointStoreV2.load(world));
    }

    @Test void concurrentNewReceiptDuringWaterEnumerationAbortsWithoutLoss()
            throws IOException {
        var session = flood();
        var snapshot = session.advance(100);
        var world = directory.resolve("racing-world.bin");
        var runner = HydrologyWorldRunnerV2.open(world, 0,
            HydrologyIncrementalAtomicEpochV2.encode(
                new HydrologyIncrementalAtomicEpochV2.Bound(
                    "initial", session.checkpoint("fixed"))));
        var key = new HydrologyTransferLedgerV2.Key(1, at(3, 2), at(4, 2));
        var sources = water(snapshot, OUTLET);
        var fired = new AtomicBoolean(false);
        var racingSources = new AbstractCollection<HydrologyGlobalSlopeRouterV2.Source>() {
            @Override public int size() { return sources.size(); }
            @Override public Iterator<HydrologyGlobalSlopeRouterV2.Source> iterator() {
                if (fired.compareAndSet(false, true)) {
                    try { runner.receive(key, 4); }
                    catch (IOException ex) { throw new UncheckedIOException(ex); }
                }
                return sources.iterator();
            }
        };
        assertThrows(IOException.class, () ->
            HydrologyIncrementalAtomicEpochV2.advance(
                runner, "should-not-commit", "fixed", session, a -> a.equals(OUTLET) ? 0 : 10, racingSources));
        assertTrue(fired.get());
        var reopened = HydrologyWorldRunnerV2.open(world, 0, "ignored");
        assertEquals(0, reopened.epoch());
        assertEquals(4, reopened.receiptBudget().pendingVolume(), 1e-12);
        assertTrue(reopened.inboxSnapshot().delivered().isEmpty());
        assertEquals("initial",
            HydrologyIncrementalAtomicEpochV2.decode(reopened.worldState()).world());
    }
    @Test void futureEpochReceiptsStayParkedUntilTheirDueEpoch()
            throws IOException {
        var session = flood();
        var snapshot = session.advance(100);
        var world = directory.resolve("future-world.bin");
        var runner = HydrologyWorldRunnerV2.open(world, 0,
            HydrologyIncrementalAtomicEpochV2.encode(
                new HydrologyIncrementalAtomicEpochV2.Bound(
                    "initial", session.checkpoint("fixed"))));
        var key = new HydrologyTransferLedgerV2.Key(3, at(3, 2), at(4, 2));
        assertTrue(runner.receive(key, 2));
        var zeros = snapshot.settled().keySet().stream()
            .map(a -> new HydrologyGlobalSlopeRouterV2.Source(a, 0)).toList();
        for (int epoch = 1; epoch <= 2; epoch++) {
            var step = HydrologyIncrementalAtomicEpochV2.advance(
                runner, "step-" + epoch, "fixed", session,
                a -> a.equals(OUTLET) ? 0 : 10, zeros);
            assertEquals(0, step.committed().flow().terminalOutflow()
                .get(OUTLET), 1e-12);
            assertEquals(2, runner.receiptBudget().pendingVolume(), 1e-12);
        }
        runner = HydrologyWorldRunnerV2.open(world, 0, "ignored");
        var delivered = HydrologyIncrementalAtomicEpochV2.advance(
            runner, "step-3", "fixed", session,
            a -> a.equals(OUTLET) ? 0 : 10, zeros);
        assertEquals(2, delivered.committed().flow().terminalOutflow()
            .get(OUTLET), 1e-12);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(Set.of(key), runner.inboxSnapshot().delivered());
    }

    @Test void partialFloodCanResumeAndAdvanceAcrossSeveralAtomicEpochs()
            throws IOException {
        var session = flood();
        session.advance(1);
        var world = directory.resolve("incremental-world.bin");
        var runner = HydrologyWorldRunnerV2.open(world, 0,
            HydrologyIncrementalAtomicEpochV2.encode(
                new HydrologyIncrementalAtomicEpochV2.Bound(
                    "start", session.checkpoint("fixed"))));
        for (int batch = 0; batch < 3; batch++) {
            session = HydrologyIncrementalAtomicEpochV2.resumeBound(
                world, "fixed", a -> a.equals(OUTLET) ? 0 : 10);
            var next = session.advance(3);
            var zeros = next.settled().keySet().stream()
                .map(a -> new HydrologyGlobalSlopeRouterV2.Source(a, 0)).toList();
            HydrologyIncrementalAtomicEpochV2.advance(
                runner, "batch-" + batch, "fixed", session,
                a -> a.equals(OUTLET) ? 0 : 10, zeros);
            runner = HydrologyWorldRunnerV2.open(world, 0, "ignored");
            assertEquals(next, HydrologyIncrementalAtomicEpochV2.resumeBound(
                world, "fixed", a -> a.equals(OUTLET) ? 0 : 10).snapshot());
            assertEquals(batch + 1, runner.epoch());
        }
        assertEquals(10, HydrologyIncrementalAtomicEpochV2.decode(
            runner.worldState()).flood().settledCount());
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
    }

    @Test void noncanonicalSeamReceiptCannotBeCountedAsSecondSource()
            throws IOException {
        var session = flood();
        var snapshot = session.advance(100);
        var world = directory.resolve("seam-world.bin");
        var runner = HydrologyWorldRunnerV2.open(world, 0,
            HydrologyIncrementalAtomicEpochV2.encode(
                new HydrologyIncrementalAtomicEpochV2.Bound(
                    "start", session.checkpoint("fixed"))));
        var alias = new HydrologyTransferLedgerV2.Key(1, at(8, 2), OUTLET);
        runner.receive(alias, 7);
        var before = HydrologyCheckpointStoreV2.load(world);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalAtomicEpochV2.advance(
                runner, "bad", "fixed", session,
                a -> a.equals(OUTLET) ? 0 : 10, water(snapshot, OUTLET)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(world));
        assertEquals(7, runner.receiptBudget().pendingVolume(), 1e-12);
    }

}
