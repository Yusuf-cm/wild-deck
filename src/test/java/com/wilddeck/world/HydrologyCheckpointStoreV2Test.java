package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyCheckpointStoreV2Test {
    @TempDir Path directory;

    private HydrologyCheckpointV2.Checkpoint checkpoint(long epoch,String world){
        var inbox=new HydrologyEpochInboxV2();
        var from=new GlobalHydrologyAddressV2.Sample(2,4,4,3);
        var to=new GlobalHydrologyAddressV2.Sample(2,4,5,3);
        inbox.receive(new HydrologyTransferLedgerV2.Key(epoch,from,to),12);
        inbox.claim(epoch);
        return HydrologyCheckpointV2.capture(epoch,world,inbox);
    }

    @Test void atomicallyReplacesBothWorldAndConsumedWaterState() throws IOException {
        Path file=directory.resolve("world.hydrology");
        HydrologyCheckpointStoreV2.save(file,checkpoint(8,"world: 🌊"));
        var first=HydrologyCheckpointStoreV2.load(file);
        assertEquals(8,first.epoch());
        assertEquals("world: 🌊",first.worldState());
        assertThrows(IllegalStateException.class,()->
            HydrologyCheckpointV2.restoreInbox(first).claim(8));
        HydrologyCheckpointStoreV2.save(file,checkpoint(9,"world: updated"));
        var second=HydrologyCheckpointStoreV2.load(file);
        assertEquals(9,second.epoch());
        assertEquals("world: updated",second.worldState());
        assertThrows(IllegalStateException.class,()->
            HydrologyCheckpointV2.restoreInbox(second).claim(9));
    }

    @Test void incompletePendingWriteNeverReplacesAuthoritativeCheckpoint()
            throws IOException {
        Path file=directory.resolve("world.hydrology");
        HydrologyCheckpointStoreV2.save(file,checkpoint(4,"committed"));
        Files.writeString(directory.resolve("world.hydrology.crashed.pending"),"incomplete");
        assertEquals("committed",HydrologyCheckpointStoreV2.load(file).worldState());
        assertEquals(4,HydrologyCheckpointStoreV2.load(file).epoch());
    }

    @Test void detectsCorruptedAndTruncatedCheckpoint() throws IOException {
        Path file=directory.resolve("world.hydrology");
        HydrologyCheckpointStoreV2.save(file,checkpoint(3,"intact"));
        byte[] bytes=Files.readAllBytes(file);
        bytes[18]^=1;
        Files.write(file,bytes);
        assertThrows(IOException.class,()->HydrologyCheckpointStoreV2.load(file));
        Files.write(file,new byte[]{1,2,3});
        assertThrows(IOException.class,()->HydrologyCheckpointStoreV2.load(file));
    }

    @Test void rejectsStaleEpochAndConflictingSameEpochButAllowsRetry() throws IOException {
        Path file=directory.resolve("world.hydrology");
        var committed=checkpoint(9,"committed");
        HydrologyCheckpointStoreV2.save(file,committed);
        HydrologyCheckpointStoreV2.save(file,committed); // idempotent retry
        assertThrows(IOException.class,()->
            HydrologyCheckpointStoreV2.save(file,checkpoint(8,"stale")));
        assertThrows(IOException.class,()->
            HydrologyCheckpointStoreV2.save(file,checkpoint(9,"conflict")));
        assertEquals(committed,HydrologyCheckpointStoreV2.load(file));
    }

    @Test void futureReceiptsMayBeAppendedButNeverRewritten() throws IOException {
        Path file=directory.resolve("receipts.chk");
        var initial=checkpoint(4,"stable-world");
        HydrologyCheckpointStoreV2.save(file,initial);
        var inbox=HydrologyCheckpointV2.restoreInbox(initial);
        var from=new GlobalHydrologyAddressV2.Sample(2,4,4,3);
        var to=new GlobalHydrologyAddressV2.Sample(2,4,5,3);
        var key=new HydrologyTransferLedgerV2.Key(5,from,to);
        inbox.receive(key,7);
        var appended=HydrologyCheckpointV2.capture(4,"stable-world",inbox);
        HydrologyCheckpointStoreV2.saveIncoming(file,appended);
        HydrologyCheckpointStoreV2.saveIncoming(file,appended);
        assertEquals(7,HydrologyCheckpointV2.restoreInbox(
            HydrologyCheckpointStoreV2.load(file)).snapshot().receipts().get(key));
        assertThrows(IOException.class,()->HydrologyCheckpointStoreV2.save(file,appended));
        assertThrows(IOException.class,()->
            HydrologyCheckpointStoreV2.saveIncoming(file,initial));
        assertThrows(IOException.class,()->
            HydrologyCheckpointStoreV2.saveIncoming(file,
                HydrologyCheckpointV2.capture(4,"different-world",inbox)));
        var altered=HydrologyEpochInboxV2.restore(inbox.snapshot());
        altered.claim(5);
        assertThrows(IOException.class,()->
            HydrologyCheckpointStoreV2.saveIncoming(file,
                HydrologyCheckpointV2.capture(4,"stable-world",altered)));
        assertEquals(appended,HydrologyCheckpointStoreV2.load(file));
    }

    @Test void missingParentFailsWithoutCreatingNewDirectories() {
        Path file=directory.resolve("missing").resolve("world.hydrology");
        assertThrows(IOException.class,()->
            HydrologyCheckpointStoreV2.save(file,checkpoint(2,"world")));
        assertFalse(Files.exists(file));
    }
}
