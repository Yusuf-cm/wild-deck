package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Sender crash/replay protocol tests. No network or remote authentication is
 * implied: the dispatcher must only acknowledge a receiver's durable accept.
 */
class HydrologyMixedResolutionOutboxV2Test {
    @TempDir Path temp;
    private static GlobalHydrologyAddressV2.Sample coarse(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(2, 4, x, y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(3, 4, x, y);
    }
    private static HydrologyMixedResolutionDischargeV2.Node node(
            GlobalHydrologyAddressV2.Sample at, double volume,
            GlobalHydrologyAddressV2.Sample next, boolean terminal) {
        return new HydrologyMixedResolutionDischargeV2.Node(at, volume, next, terminal);
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint initial() {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(0, "terrain:initial",
            new HydrologyMixedResolutionEpochV2.Snapshot(0, Map.of(), Set.of()));
    }
    private static void init(Path path) throws IOException {
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path, initial());
    }

    @Test void crashAfterSenderCommitRetainsWaterBeforeAnyDispatch() throws IOException {
        Path sender = temp.resolve("sender.bin");
        init(sender);
        var source = coarse(4, 4);
        var landing = fine(10, 8);
        var committed = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            sender, initial(), "eroded:1", List.of(node(source, 7, landing, false)),
            Set.of(), 2);
        assertEquals(1, committed.checkpoint().epoch());
        assertEquals(7.0, committed.flow().pendingOutflow().get(
            HydrologyGlobalCatchmentsV2.coordinate(landing)));
        var recovered = HydrologyMixedResolutionCheckpointStoreV2.load(sender);
        assertEquals("eroded:1", recovered.worldState());
        assertEquals(committed.checkpoint(), recovered);
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.pending(recovered).size());
        assertEquals(7.0, HydrologyMixedResolutionCheckpointStoreV2.pending(recovered)
            .values().iterator().next());
    }

    @Test void durableReceiverAcceptThenLostAckIsSafeToReplay() throws IOException {
        Path sender = temp.resolve("sender.bin");
        Path receiver = temp.resolve("receiver.bin");
        init(sender);
        init(receiver);
        var from = coarse(4, 4);
        var landing = coarse(5, 4);
        var mouth = fine(12, 8);
        var graph = List.of(node(from, 7, fine(10, 8), false));
        var senderCommit = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            sender, initial(), "sender:1", graph, Set.of(), 2);
        var outgoing = HydrologyMixedResolutionCheckpointStoreV2.pending(senderCommit.checkpoint());
        var entry = outgoing.entrySet().iterator().next();

        // Receiver persists acceptance. The process dies before the sender
        // observes the acknowledgment; retry must not inject a second 7.
        var accepted = HydrologyMixedResolutionCheckpointStoreV2.receive(
            receiver, initial(), entry.getKey(), entry.getValue());
        assertEquals(accepted, HydrologyMixedResolutionCheckpointStoreV2.load(receiver));
        var afterRestart = HydrologyMixedResolutionCheckpointStoreV2.receive(
            receiver, HydrologyMixedResolutionCheckpointStoreV2.load(receiver),
            entry.getKey(), entry.getValue());
        assertEquals(1, afterRestart.inbox().receipts().size());

        var senderRecovered = HydrologyMixedResolutionCheckpointStoreV2.load(sender);
        assertEquals(outgoing, HydrologyMixedResolutionCheckpointStoreV2.pending(senderRecovered));
        var acked = HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
            sender, senderRecovered, entry.getKey(), entry.getValue());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.pending(acked).isEmpty());
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.load(sender)
            .acknowledged().size());
        assertEquals(acked, HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
            sender, acked, entry.getKey(), entry.getValue()));

        var downstream = List.of(node(landing, 3, mouth, false),
                                 node(mouth, 5, null, true));
        var early = HydrologyMixedResolutionCheckpointStoreV2.advance(
            receiver, afterRestart, "receiver:1", downstream, Set.of(mouth));
        assertEquals(8.0, early.flow().terminalOutflow().get(mouth));
        var due = HydrologyMixedResolutionCheckpointStoreV2.advance(
            receiver, HydrologyMixedResolutionCheckpointStoreV2.load(receiver),
            "receiver:2", downstream, Set.of(mouth));
        assertEquals(15.0, due.flow().terminalOutflow().get(mouth));
        assertEquals(1, due.checkpoint().inbox().delivered().size());
        var later = HydrologyMixedResolutionCheckpointStoreV2.advance(
            receiver, due.checkpoint(), "receiver:3", downstream, Set.of(mouth));
        assertEquals(8.0, later.flow().terminalOutflow().get(mouth));
    }

    @Test void twoSourceConfluencePreservesIndividualReceiptsAndConservesWater()
            throws IOException {
        Path sender = temp.resolve("sender.bin");
        Path receiver = temp.resolve("receiver.bin");
        init(sender);
        init(receiver);
        var a = coarse(4, 4);
        var b = coarse(5, 5);
        var landing = coarse(5, 4);
        var mouth = fine(12, 8);
        var upstream = List.of(node(a, 7, fine(10, 8), false),
                               node(b, 11, landing, false));
        var sent = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            sender, initial(), "sender:1", upstream, Set.of(), 1);
        var pending = HydrologyMixedResolutionCheckpointStoreV2.pending(sent.checkpoint());
        assertEquals(2, pending.size());
        assertEquals(18.0, pending.values().stream().mapToDouble(Double::doubleValue).sum());
        var accepted = initial();
        for (var entry : pending.entrySet())
            accepted = HydrologyMixedResolutionCheckpointStoreV2.receive(
                receiver, accepted, entry.getKey(), entry.getValue());
        var downstream = List.of(node(landing, 3, mouth, false),
                                 node(mouth, 5, null, true));
        var received = HydrologyMixedResolutionCheckpointStoreV2.advance(
            receiver, accepted, "receiver:1", downstream, Set.of(mouth));
        assertEquals(26.0, received.flow().terminalOutflow().get(mouth));
        assertEquals(2, received.checkpoint().inbox().delivered().size());
        var senderState = HydrologyMixedResolutionCheckpointStoreV2.load(sender);
        for (var entry : pending.entrySet())
            senderState = HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
                sender, senderState, entry.getKey(), entry.getValue());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.pending(senderState).isEmpty());
        assertEquals(2, HydrologyMixedResolutionCheckpointStoreV2.load(sender)
            .acknowledged().size());
    }

    @Test void failedSolveAndInvalidExportCannotPartiallyCommitSender() throws IOException {
        Path sender = temp.resolve("sender.bin");
        init(sender);
        var source = coarse(4, 4);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
                sender, initial(), "bad", List.of(node(source, 7, null, false)),
                Set.of(), 1));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
                sender, initial(), "bad", List.of(node(source, 7, coarse(5, 4), false)),
                Set.of(), 0));
        assertEquals(initial(), HydrologyMixedResolutionCheckpointStoreV2.load(sender));
    }

    @Test void acknowledgmentChecksVolumeAndStaleWriterAndRetainsHistory() throws IOException {
        Path sender = temp.resolve("sender.bin");
        init(sender);
        var graph = List.of(node(coarse(4, 4), 7, coarse(5, 4), false));
        var committed = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            sender, initial(), "step1", graph, Set.of(), 1);
        var entry = HydrologyMixedResolutionCheckpointStoreV2.pending(
            committed.checkpoint()).entrySet().iterator().next();
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
                sender, committed.checkpoint(), entry.getKey(), 8));
        // An obsolete snapshot has no record of this receipt; reject it
        // before attempting any acknowledgment or checkpoint mutation.
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
                sender, initial(), entry.getKey(), entry.getValue()));
        var acked = HydrologyMixedResolutionCheckpointStoreV2.acknowledgePersisted(
            sender, committed.checkpoint(), entry.getKey(), entry.getValue());
        assertThrows(IOException.class, () ->
            HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
                sender, committed.checkpoint(), "stale", graph, Set.of(), 2));
        assertEquals(1, acked.outbox().size());
        assertEquals(1, acked.acknowledged().size());
        assertThrows(UnsupportedOperationException.class, () ->
            acked.outbox().clear());
        var next = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            sender, acked, "step2", graph, Set.of(), 2);
        assertEquals(2, next.checkpoint().outbox().size());
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.pending(
            next.checkpoint()).size());
    }

    @Test void legacyVersionOneCheckpointLoadsAndUpgradesOnNextCommit() throws Exception {
        Path sender = temp.resolve("legacy.bin");
        var old = initial();
        byte[] world = old.worldState().getBytes(StandardCharsets.UTF_8);
        byte[] inbox = HydrologyMixedResolutionEpochV2.encode(
            old.inbox()).getBytes(StandardCharsets.US_ASCII);
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d524332);
            out.writeInt(1);
            out.writeLong(old.epoch());
            out.writeInt(world.length);
            out.write(world);
            out.writeInt(inbox.length);
            out.write(inbox);
        }
        byte[] body = bytes.toByteArray();
        bytes.write(MessageDigest.getInstance("SHA-256").digest(body));
        Files.write(sender, bytes.toByteArray());
        assertEquals(old, HydrologyMixedResolutionCheckpointStoreV2.load(sender));
        var next = HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            sender, old, "upgraded", List.of(node(coarse(4, 4), 7,
                coarse(5, 4), false)), Set.of(), 1);
        assertEquals(next.checkpoint(), HydrologyMixedResolutionCheckpointStoreV2.load(sender));
        assertEquals(1, HydrologyMixedResolutionCheckpointStoreV2.pending(
            next.checkpoint()).size());
    }

    @Test void deterministicCheckpointBytesAndCorruptOutboxAreRejected() throws Exception {
        Path first = temp.resolve("a.bin");
        Path second = temp.resolve("b.bin");
        init(first);
        init(second);
        var a = node(coarse(4, 4), 7, coarse(5, 4), false);
        var b = node(coarse(5, 5), 11, coarse(5, 4), false);
        HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            first, initial(), "step1", List.of(a, b), Set.of(), 1);
        HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            second, initial(), "step1", List.of(b, a), Set.of(), 1);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        var damaged = Files.readAllBytes(first);
        damaged[damaged.length - 1] ^= 0x20;
        Files.write(first, damaged);
        assertThrows(IOException.class, () ->
            HydrologyMixedResolutionCheckpointStoreV2.load(first));
    }
}
