package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentSenderOutboxV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A=at(0,2),B=at(1,2),C=at(2,2),D=at(1,3);

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(
            long source,long arrival,GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(source,arrival,from,to);
    }
    private static HydrologySedimentReceiptInboxV2.Receipt r(long source) {
        return receipt(source,source+1,A,B);
    }
    private static HydrologySedimentFrontierTransfersV2.Outgoing outgoing(
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> entries) {
        double sum=entries.values().stream().mapToDouble(Double::doubleValue).sum();
        return new HydrologySedimentFrontierTransfersV2.Outgoing(entries,sum);
    }
    private static HydrologyCheckpointV2.Checkpoint receiver(Path path) throws IOException {
        var initial=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld("test-world"),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,initial);
        return initial;
    }

    @Test void journalCodecIsCanonicalAndDetectsCorruption() {
        var one=r(0);
        var two=receipt(1,3,D,B);
        var left=HydrologySedimentSenderOutboxV2.append(
            HydrologySedimentSenderOutboxV2.append(
                HydrologySedimentSenderOutboxV2.empty(),outgoing(Map.of(one,2.5))),
                outgoing(Map.of(two,1.25)));
        var right=HydrologySedimentSenderOutboxV2.append(
            HydrologySedimentSenderOutboxV2.empty(),outgoing(Map.of(two,1.25,one,2.5)));
        String encoded=HydrologySedimentSenderOutboxV2.encode(left);
        assertEquals(encoded,HydrologySedimentSenderOutboxV2.encode(right));
        assertEquals(left,HydrologySedimentSenderOutboxV2.decode(encoded));
        assertThrows(IllegalArgumentException.class,()->HydrologySedimentSenderOutboxV2.decode(
            encoded.replace("0x1.4p1","0x1.8p1")));
        assertThrows(IllegalArgumentException.class,()->HydrologySedimentSenderOutboxV2.decode(
            encoded+"garbage"));
    }

    @Test void duplicateReplayIsIdempotentButSenderForkAndAlteredMassFail() {
        var first=HydrologySedimentSenderOutboxV2.append(
            HydrologySedimentSenderOutboxV2.empty(),outgoing(Map.of(r(0),4.0)));
        assertSame(first,HydrologySedimentSenderOutboxV2.append(
            first,outgoing(Map.of(r(0),4.0))));
        assertThrows(IllegalArgumentException.class,()->HydrologySedimentSenderOutboxV2.append(
            first,outgoing(Map.of(r(0),5.0))));
        var fork=receipt(0,1,A,at(0,3));
        assertThrows(IllegalArgumentException.class,()->HydrologySedimentSenderOutboxV2.append(
            first,outgoing(Map.of(fork,4.0))));
        assertThrows(IllegalArgumentException.class,()->HydrologySedimentSenderOutboxV2.append(
            first,new HydrologySedimentFrontierTransfersV2.Outgoing(Map.of(r(2),1.0),2.0)));
    }

    @Test void senderMustPersistBeforeAckAndAckRequiresActualReceiverCheckpoint()
            throws IOException {
        Path sender=directory.resolve("sender.chk");
        Path receiver=directory.resolve("receiver.chk");
        var start=HydrologySedimentSenderOutboxV2.initialize(sender);
        var journal=HydrologySedimentSenderOutboxV2.appendDurable(sender,start,
            outgoing(Map.of(r(0),7.0)));
        assertEquals(journal,HydrologyCheckpointStoreV2.load(sender));
        var initialReceiver=receiver(receiver);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentSenderOutboxV2.acknowledgeDurable(sender,journal,r(0),receiver));
        assertEquals(journal,HydrologyCheckpointStoreV2.load(sender));
        HydrologySedimentCheckpointV2.receive(receiver,initialReceiver,r(0),7.0);
        var ack=HydrologySedimentSenderOutboxV2.acknowledgeDurable(
            sender,journal,r(0),receiver);
        assertTrue(HydrologySedimentSenderOutboxV2.restore(ack).acknowledged().contains(r(0)));
        assertEquals(7.0,HydrologySedimentSenderOutboxV2.outgoing(
            HydrologySedimentSenderOutboxV2.restore(ack)).pendingMass(),0.0);
        assertEquals(ack,HydrologySedimentSenderOutboxV2.acknowledgeDurable(
            sender,ack,r(0),receiver));
        assertEquals(ack,HydrologyCheckpointStoreV2.load(sender));
    }

    @Test void lostReceiverCheckpointCanBeRecoveredEvenAfterAcknowledgment()
            throws IOException {
        Path sender=directory.resolve("sender-lost.chk");
        Path receiver=directory.resolve("receiver-lost.chk");
        var initial=HydrologySedimentSenderOutboxV2.initialize(sender);
        var saved=HydrologySedimentSenderOutboxV2.appendDurable(
            sender,initial,outgoing(Map.of(r(0),2.0,r(1),3.0)));
        var receiverInitial=receiver(receiver);
        var accepted=HydrologySedimentCheckpointV2.receiveBatch(receiver,receiverInitial,
            Map.of(r(0),2.0,r(1),3.0));
        var ack=HydrologySedimentSenderOutboxV2.acknowledgeDurable(sender,saved,r(0),receiver);
        ack=HydrologySedimentSenderOutboxV2.acknowledgeDurable(sender,ack,r(1),receiver);
        var restored=HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(sender));
        assertTrue(HydrologySedimentSenderOutboxV2.replayMissing(restored,
            HydrologySedimentCheckpointV2.decode(accepted.worldState()).sediment()).isEmpty());
        // Simulate restore from an older receiver backup without deleting sender history.
        assertEquals(Map.of(r(0),2.0,r(1),3.0),
            HydrologySedimentSenderOutboxV2.replayMissing(restored,
                HydrologySedimentCheckpointV2.decode(receiverInitial.worldState()).sediment()));
    }

    @Test void staleWriterCannotEraseNewJournalOrAcknowledgments() throws IOException {
        Path sender=directory.resolve("stale.chk");
        var initial=HydrologySedimentSenderOutboxV2.initialize(sender);
        var first=HydrologySedimentSenderOutboxV2.appendDurable(
            sender,initial,outgoing(Map.of(r(0),2.0)));
        assertThrows(IOException.class,()->HydrologySedimentSenderOutboxV2.appendDurable(
            sender,initial,outgoing(Map.of(r(1),3.0))));
        assertEquals(first,HydrologyCheckpointStoreV2.load(sender));
        assertThrows(IOException.class,()->HydrologySedimentSenderOutboxV2.initialize(sender));
    }

    @Test void mismatchedReceiverMassCannotAcknowledgeOrHideConflicts() throws IOException {
        Path sender=directory.resolve("sender-mismatch.chk");
        Path receiver=directory.resolve("receiver-mismatch.chk");
        var initial=HydrologySedimentSenderOutboxV2.initialize(sender);
        var saved=HydrologySedimentSenderOutboxV2.appendDurable(
            sender,initial,outgoing(Map.of(r(0),2.0)));
        var receiverInitial=receiver(receiver);
        var wrong=HydrologySedimentCheckpointV2.receive(receiver,receiverInitial,r(0),3.0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentSenderOutboxV2.acknowledgeDurable(sender,saved,r(0),receiver));
        var senderSnapshot=HydrologySedimentSenderOutboxV2.restore(saved);
        var receiverSnapshot=HydrologySedimentCheckpointV2.decode(wrong.worldState()).sediment();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentSenderOutboxV2.replayMissing(senderSnapshot,receiverSnapshot));
        assertEquals(saved,HydrologyCheckpointStoreV2.load(sender));
    }

    @Test void seededOutboxAndRestartConserveIndependentConfluences() throws IOException {
        Path sender=directory.resolve("seeded.chk");
        var checkpoint=HydrologySedimentSenderOutboxV2.initialize(sender);
        var random=new Random(713);
        double total=0;
        for(int i=0;i<80;i++) {
            double value=1+random.nextInt(100);
            total+=value;
            var key=receipt(i,i+1,i%2==0?A:D,B);
            checkpoint=HydrologySedimentSenderOutboxV2.appendDurable(
                sender,checkpoint,outgoing(Map.of(key,value)));
        }
        var restored=HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(sender));
        assertEquals(80,restored.receipts().size());
        assertEquals(total,HydrologySedimentSenderOutboxV2.outgoing(restored).pendingMass(),1e-9);
        assertEquals(restored,HydrologySedimentSenderOutboxV2.decode(
            HydrologySedimentSenderOutboxV2.encode(restored)));
    }
}
