package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyMixedResolutionCheckpointStoreV2Test {
    @TempDir Path temp;
    private static GlobalHydrologyAddressV2.Sample coarse(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static GlobalHydrologyAddressV2.Sample fine(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(3,4,x,y);
    }
    private static HydrologyMixedResolutionDischargeV2.Node node(
            GlobalHydrologyAddressV2.Sample at,double water,
            GlobalHydrologyAddressV2.Sample next,boolean terminal) {
        return new HydrologyMixedResolutionDischargeV2.Node(at,water,next,terminal);
    }
    private static HydrologyMixedResolutionCheckpointStoreV2.Checkpoint initial() {
        return new HydrologyMixedResolutionCheckpointStoreV2.Checkpoint(0,"terrain:initial",
            new HydrologyMixedResolutionEpochV2.Snapshot(0,Map.of(),Set.of()));
    }

    @Test void durableMixedLodReceiptIsConsumedOnceAfterRestart() throws IOException {
        Path path=temp.resolve("water.bin");
        var start=initial();
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,start);
        var from=coarse(4,4);var landing=coarse(5,4);var mouth=fine(12,8);
        var key=new HydrologyMixedResolutionEpochV2.Receipt(0,from,fine(10,8));
        var accepted=HydrologyMixedResolutionCheckpointStoreV2.receive(path,start,key,7);
        assertEquals(accepted,HydrologyMixedResolutionCheckpointStoreV2.load(path));
        var graph=List.of(node(landing,3,mouth,false),node(mouth,5,null,true));
        var committed=HydrologyMixedResolutionCheckpointStoreV2.advance(path,
            HydrologyMixedResolutionCheckpointStoreV2.load(path),"terrain:eroded",graph,Set.of(mouth));
        assertEquals(15.0,committed.flow().terminalOutflow().get(mouth));
        assertEquals("terrain:eroded",HydrologyMixedResolutionCheckpointStoreV2.load(path).worldState());
        assertTrue(HydrologyMixedResolutionCheckpointStoreV2.load(path).inbox().delivered().contains(key));
        var again=HydrologyMixedResolutionCheckpointStoreV2.advance(path,
            HydrologyMixedResolutionCheckpointStoreV2.load(path),"terrain:next",graph,Set.of(mouth));
        assertEquals(8.0,again.flow().terminalOutflow().get(mouth));
        assertEquals(2,HydrologyMixedResolutionCheckpointStoreV2.load(path).epoch());
    }

    @Test void staleWriterAndConflictingReplayCannotOverwriteReceipt() throws IOException {
        Path path=temp.resolve("water.bin");
        var start=initial();
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,start);
        var key=new HydrologyMixedResolutionEpochV2.Receipt(0,coarse(4,4),coarse(5,4));
        var accepted=HydrologyMixedResolutionCheckpointStoreV2.receive(path,start,key,7);
        assertThrows(IOException.class,()->
            HydrologyMixedResolutionCheckpointStoreV2.receive(path,start,key,7));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionCheckpointStoreV2.receive(path,accepted,key,8));
        assertEquals(accepted,HydrologyMixedResolutionCheckpointStoreV2.receive(
            path,accepted,new HydrologyMixedResolutionEpochV2.Receipt(
                0,fine(8,8),coarse(5,4)),7));
        assertEquals(accepted,HydrologyMixedResolutionCheckpointStoreV2.load(path));
    }

    @Test void invalidSolveLeavesDurableReceiptAndWorldUntouched() throws IOException {
        Path path=temp.resolve("water.bin");
        var start=initial();
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,start);
        var accepted=HydrologyMixedResolutionCheckpointStoreV2.receive(path,start,
            new HydrologyMixedResolutionEpochV2.Receipt(0,coarse(4,4),coarse(5,4)),Double.MAX_VALUE);
        var graph=List.of(node(coarse(5,4),Double.MAX_VALUE,null,true));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionCheckpointStoreV2.advance(path,accepted,"bad",graph,
                Set.of(coarse(5,4))));
        assertEquals(accepted,HydrologyMixedResolutionCheckpointStoreV2.load(path));
    }

    @Test void unloadedReceiptSurvivesSeveralCommittedEpochs() throws IOException {
        Path path=temp.resolve("water.bin");
        var start=initial();
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,start);
        var landing=coarse(5,4);var other=coarse(9,4);
        var key=new HydrologyMixedResolutionEpochV2.Receipt(0,coarse(4,4),landing);
        var accepted=HydrologyMixedResolutionCheckpointStoreV2.receive(path,start,key,9);
        var first=HydrologyMixedResolutionCheckpointStoreV2.advance(path,accepted,"step1",
            List.of(node(other,2,null,true)),Set.of(other));
        assertTrue(first.checkpoint().inbox().delivered().isEmpty());
        var second=HydrologyMixedResolutionCheckpointStoreV2.advance(path,
            HydrologyMixedResolutionCheckpointStoreV2.load(path),"step2",
            List.of(node(landing,1,null,true)),Set.of(landing));
        assertEquals(10.0,second.flow().terminalOutflow().get(landing));
        assertEquals(Set.of(key),second.checkpoint().inbox().delivered());
    }

    @Test void checksumCorruptionIsDetectedAndInitializeDoesNotClobber() throws IOException {
        Path path=temp.resolve("water.bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,initial());
        assertThrows(IOException.class,()->
            HydrologyMixedResolutionCheckpointStoreV2.initialize(path,initial()));
        var bytes=Files.readAllBytes(path);
        bytes[bytes.length-1]^=0x01;
        Files.write(path,bytes);
        assertThrows(IOException.class,()->HydrologyMixedResolutionCheckpointStoreV2.load(path));
    }
}
