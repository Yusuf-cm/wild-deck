package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalResolutionContractV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }

    private static HydrologyEpochInboxV2 inbox() {
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(
            1, at(0, 2), at(7, 2)), 5);
        return inbox;
    }

    @Test void strictSchedulerRejectsSameLevelWrongDivisionsBeforeProvider() {
        var pending = inbox().snapshot();
        var loads = new AtomicInteger();
        HydrologyRegionSchedulerV2.Provider provider = region -> {
            loads.incrementAndGet();
            return List.of();
        };
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySphericalRegionSchedulerV2.loadWithPending(
                List.of(), pending, 1, 4, 0, 1, provider));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySphericalRegionSchedulerV2.loadWithPending(
                List.of(), pending, 1, 0, 0, 1, provider));
        assertEquals(0, loads.get());
        assertEquals(1, HydrologySphericalRegionSchedulerV2.loadWithPending(
            List.of(), pending, 1, 2, 0, 1, provider).size());
        assertEquals(1, loads.get());
        assertEquals(5, HydrologyReceiptBudgetV2.summarize(
            pending).pendingVolume(), 1e-12);
    }

    @Test void strictWorldRunnerDoesNotConsumeWrongResolutionReceipt()
            throws IOException {
        var path = directory.resolve("world.bin");
        var runner = HydrologyWorldRunnerV2.open(path, 0, "initial");
        var key = new HydrologyTransferLedgerV2.Key(1, at(0, 2), at(7, 2));
        assertTrue(runner.receive(key, 5));
        var loads = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () ->
            runner.advanceSphericalPendingRegionsAndQueue(
                "next", List.of(), 1, 4, 0, 1,
                region -> { loads.incrementAndGet(); return List.of(); }));
        assertEquals(0, loads.get());
        var recovered = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(0, recovered.epoch());
        assertEquals(5, recovered.receiptBudget().pendingVolume(), 1e-12);
        assertFalse(recovered.inboxSnapshot().delivered().contains(key));
    }
}
