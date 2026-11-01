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
    @Test void exportedMixedLodConfluenceSurvivesUnloadedBoundaryAndReplay() throws IOException {
        var a=coarse(4,4);var b=coarse(5,5);var landing=coarse(5,4);
        var mouth=fine(12,8);
        var upstream=List.of(node(a,7,fine(10,8),false),
                             node(b,11,landing,false));
        var upstreamFlow=HydrologyMixedResolutionDischargeV2.solve(upstream,Set.of());
        var outgoing=HydrologyMixedResolutionEpochV2.exportFrontier(1,upstream,upstreamFlow);
        assertEquals(2,outgoing.size());
        assertEquals(18.0,upstreamFlow.pendingOutflow().get(
            HydrologyGlobalCatchmentsV2.coordinate(landing)));

        Path path=temp.resolve("receiving-region.bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(path,initial());
        var expected=HydrologyMixedResolutionCheckpointStoreV2.load(path);
        for (var entry : outgoing.entrySet())
            expected=HydrologyMixedResolutionCheckpointStoreV2.receive(
                path,expected,entry.getKey(),entry.getValue());
        var afterRestart=HydrologyMixedResolutionCheckpointStoreV2.load(path);
        for (var entry : outgoing.entrySet())
            afterRestart=HydrologyMixedResolutionCheckpointStoreV2.receive(
                path,afterRestart,entry.getKey(),entry.getValue());
        assertEquals(2,afterRestart.inbox().receipts().size());
        var receiving=List.of(node(landing,3,mouth,false),node(mouth,5,null,true));
        var committed=HydrologyMixedResolutionCheckpointStoreV2.advance(
            path,afterRestart,"received",receiving,Set.of(mouth));
        assertEquals(26.0,committed.flow().terminalOutflow().get(mouth));
        assertEquals(2,committed.checkpoint().inbox().delivered().size());
        var next=HydrologyMixedResolutionCheckpointStoreV2.advance(path,
            HydrologyMixedResolutionCheckpointStoreV2.load(path),"later",
            receiving,Set.of(mouth));
        assertEquals(8.0,next.flow().terminalOutflow().get(mouth));
    }

    @Test void exporterRejectsUnresolvedSinksAndMismatchedFlow() {
        var a=coarse(4,4);var b=coarse(5,4);
        var sink=List.of(node(a,7,null,false));
        var unresolved=HydrologyMixedResolutionDischargeV2.solve(sink,Set.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.exportFrontier(1,sink,unresolved));
        var routed=List.of(node(a,7,b,false));
        var result=HydrologyMixedResolutionDischargeV2.solve(routed,Set.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.exportFrontier(1,sink,result));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionEpochV2.exportFrontier(-1,routed,result));
    }

    @Test void threeRegionsRouteConservedWaterThroughTwoUnloadedFrontiers() throws IOException {
        var source=coarse(4,4);var middle=coarse(5,4);
        var destination=fine(12,8);var mouth=coarse(7,4);
        var firstGraph=List.of(node(source,7,fine(10,8),false));
        var first=HydrologyMixedResolutionDischargeV2.solve(firstGraph,Set.of());
        var firstReceipts=HydrologyMixedResolutionEpochV2.exportFrontier(1,firstGraph,first);

        Path middleFile=temp.resolve("middle.bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(middleFile,initial());
        var middleCheckpoint=HydrologyMixedResolutionCheckpointStoreV2.load(middleFile);
        for (var entry:firstReceipts.entrySet())
            middleCheckpoint=HydrologyMixedResolutionCheckpointStoreV2.receive(
                middleFile,middleCheckpoint,entry.getKey(),entry.getValue());
        var middleGraph=List.of(node(middle,3,fine(12,8),false));
        var middleCommitted=HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            middleFile,middleCheckpoint,"middle:advanced",middleGraph,Set.of(),2);
        assertEquals(10.0,middleCommitted.flow().pendingOutflow().get(
            HydrologyGlobalCatchmentsV2.coordinate(destination)));
        var secondReceipts=HydrologyMixedResolutionCheckpointStoreV2.pending(
            middleCommitted.checkpoint());

        Path finalFile=temp.resolve("final.bin");
        HydrologyMixedResolutionCheckpointStoreV2.initialize(finalFile,initial());
        var finalCheckpoint=HydrologyMixedResolutionCheckpointStoreV2.load(finalFile);
        for (var entry:secondReceipts.entrySet())
            finalCheckpoint=HydrologyMixedResolutionCheckpointStoreV2.receive(
                finalFile,finalCheckpoint,entry.getKey(),entry.getValue());
        var finalGraph=List.of(node(destination,5,mouth,false),node(mouth,2,null,true));
        var notDue=HydrologyMixedResolutionCheckpointStoreV2.advance(
            finalFile,finalCheckpoint,"final:before",finalGraph,Set.of(mouth));
        assertEquals(7.0,notDue.flow().terminalOutflow().get(mouth));
        var delivered=HydrologyMixedResolutionCheckpointStoreV2.advance(
            finalFile,HydrologyMixedResolutionCheckpointStoreV2.load(finalFile),
            "final:received",finalGraph,Set.of(mouth));
        assertEquals(17.0,delivered.flow().terminalOutflow().get(mouth));
        assertEquals(1,delivered.checkpoint().inbox().delivered().size());
        assertEquals(1,middleCommitted.checkpoint().inbox().delivered().size());
    }

    @Test void plainAdvanceRejectsUnqueuedWaterWithoutMutatingWorldOrInbox()
            throws IOException {
        Path file=temp.resolve("unqueued.bin");
        var initial=initial();
        HydrologyMixedResolutionCheckpointStoreV2.initialize(file,initial);
        var from=coarse(4,4);
        var landing=coarse(5,4);
        var key=new HydrologyMixedResolutionEpochV2.Receipt(0,coarse(3,4),from);
        var accepted=HydrologyMixedResolutionCheckpointStoreV2.receive(file,initial,key,2);
        var graph=List.of(node(from,7,landing,false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyMixedResolutionCheckpointStoreV2.advance(
                file,accepted,"lost-water",graph,Set.of()));
        assertEquals(accepted,HydrologyMixedResolutionCheckpointStoreV2.load(file));
        var queued=HydrologyMixedResolutionCheckpointStoreV2.advanceAndQueue(
            file,accepted,"conserved-water",graph,Set.of(),1);
        assertEquals(9.0,queued.flow().pendingOutflow().get(
            HydrologyGlobalCatchmentsV2.coordinate(landing)));
        assertEquals(9.0,HydrologyMixedResolutionCheckpointStoreV2.pending(
            queued.checkpoint()).values().iterator().next());
        assertEquals("conserved-water",HydrologyMixedResolutionCheckpointStoreV2.load(file).worldState());
    }

}
