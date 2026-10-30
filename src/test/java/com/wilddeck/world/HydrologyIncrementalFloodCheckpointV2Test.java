package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyIncrementalFloodCheckpointV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }

    private static final GlobalHydrologyAddressV2.Sample OUTLET = at(0, 2);
    private static final GlobalHydrologyAddressV2.Sample PIT = at(4, 2);

    private static HydrologyGlobalSlopeRouterV2.Elevations terrain() {
        return a -> a.equals(OUTLET) ? 0 : a.equals(PIT) ? -5 : 10;
    }

    @Test void durableReplayAcrossBatchesPreservesFrontierAndFinalCatchments()
            throws IOException {
        var path = directory.resolve("priority-flood.bin");
        var continuous = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), terrain());
        var resumed = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), terrain());
        for (int batch : List.of(1, 3, 7, 5, 10)) {
            var expected = continuous.advance(batch);
            var before = resumed.advance(batch);
            assertEquals(expected, before);
            var checkpoint = resumed.checkpoint("terrain-sha256-v1");
            HydrologyIncrementalFloodCheckpointStoreV2.save(path, checkpoint);
            assertEquals(checkpoint,
                HydrologyIncrementalFloodCheckpointStoreV2.load(path));
            resumed = HydrologyIncrementalPriorityFloodV2.resume(
                HydrologyIncrementalFloodCheckpointStoreV2.load(path),
                "terrain-sha256-v1", terrain());
            assertEquals(before, resumed.snapshot());
        }
        assertTrue(resumed.snapshot().complete());
        assertEquals(26, resumed.snapshot().settled().size());
        assertEquals(continuous.snapshot(), resumed.snapshot());
        assertEquals(new HydrologyRiverIdentityV2.CatchmentId(OUTLET),
            resumed.snapshot().settled().get(PIT).catchment());
        assertEquals(10, resumed.snapshot().settled().get(PIT).spillElevation());
        assertEquals(continuous.snapshot().knownRoutes(),
            resumed.snapshot().knownRoutes());
    }

    @Test void terrainDriftForgedStateAndRevisionMismatchFailClosed()
            throws IOException {
        var path = directory.resolve("flood.bin");
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), terrain());
        session.advance(5);
        var checkpoint = session.checkpoint("terrain-revision-A");
        HydrologyIncrementalFloodCheckpointStoreV2.save(path, checkpoint);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.resume(checkpoint,
                "terrain-revision-B", terrain()));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.resume(checkpoint,
                "terrain-revision-A", a -> a.equals(OUTLET) ? 0 : 99));
        var forged = new HydrologyIncrementalPriorityFloodV2.Checkpoint(
            checkpoint.terrainRevision(), checkpoint.verifiedOutlets(),
            checkpoint.settledCount(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyIncrementalPriorityFloodV2.resume(forged,
                "terrain-revision-A", terrain()));
        assertEquals(checkpoint,
            HydrologyIncrementalFloodCheckpointStoreV2.load(path));
    }

    @Test void corruptTruncatedAndConflictingCheckpointsNeverReplaceLastGoodState()
            throws IOException {
        var path = directory.resolve("flood.bin");
        var session = HydrologyIncrementalPriorityFloodV2.start(
            Set.of(OUTLET), terrain());
        session.advance(2);
        var first = session.checkpoint("revision-1");
        HydrologyIncrementalFloodCheckpointStoreV2.save(path, first);
        HydrologyIncrementalFloodCheckpointStoreV2.save(path, first);
        var corrupt = directory.resolve("corrupt.bin");
        byte[] data = Files.readAllBytes(path);
        data[15] ^= 1;
        Files.write(corrupt, data);
        assertThrows(IOException.class, () ->
            HydrologyIncrementalFloodCheckpointStoreV2.load(corrupt));
        Files.write(corrupt, Arrays.copyOf(data, 9));
        assertThrows(IOException.class, () ->
            HydrologyIncrementalFloodCheckpointStoreV2.load(corrupt));

        var differentRevision = new HydrologyIncrementalPriorityFloodV2.Checkpoint(
            "revision-2", first.verifiedOutlets(), first.settledCount(),
            first.stateSha256());
        assertThrows(IOException.class, () ->
            HydrologyIncrementalFloodCheckpointStoreV2.save(path, differentRevision));
        var forged = new HydrologyIncrementalPriorityFloodV2.Checkpoint(
            first.terrainRevision(), first.verifiedOutlets(),
            first.settledCount(), "f".repeat(64));
        assertThrows(IOException.class, () ->
            HydrologyIncrementalFloodCheckpointStoreV2.save(path, forged));

        session.advance(4);
        var second = session.checkpoint("revision-1");
        HydrologyIncrementalFloodCheckpointStoreV2.save(path, second);
        assertThrows(IOException.class, () ->
            HydrologyIncrementalFloodCheckpointStoreV2.save(path, first));
        assertEquals(second,
            HydrologyIncrementalFloodCheckpointStoreV2.load(path));
    }

    @Test void polarSeamAndOutletOrderingSurviveRestart() throws IOException {
        var north = at(0, 0);
        var south = at(0, 4);
        var first = HydrologyIncrementalPriorityFloodV2.start(
            new LinkedHashSet<>(List.of(north, south)), a -> 10);
        var second = HydrologyIncrementalPriorityFloodV2.start(
            new LinkedHashSet<>(List.of(south, north)), a -> 10);
        first.advance(8);
        second.advance(8);
        assertEquals(first.checkpoint("fixed"), second.checkpoint("fixed"));
        var path = directory.resolve("poles.bin");
        HydrologyIncrementalFloodCheckpointStoreV2.save(
            path, first.checkpoint("fixed"));
        var restored = HydrologyIncrementalPriorityFloodV2.resume(
            HydrologyIncrementalFloodCheckpointStoreV2.load(path),
            "fixed", a -> 10);
        assertEquals(first.snapshot(), restored.snapshot());
        assertEquals(first.advance(100), restored.advance(100));
        assertTrue(restored.snapshot().complete());
    }
}
