package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLakeCarryoverStateV2Test {
    @TempDir Path directory;
    private static final GlobalHydrologyAddressV2.Sample A=sample(2,2);
    private static final GlobalHydrologyAddressV2.Sample B=sample(3,2);
    private static final GlobalHydrologyAddressV2.Sample OUT=sample(6,2);

    private static GlobalHydrologyAddressV2.Sample sample(int x,int y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static HydrologySeasonalGlobalRoutingV2.Result routed(double a) {
        var nodes=List.of(new HydrologyFlowGraphV2.Node(A,a,null,false),
            new HydrologyFlowGraphV2.Node(B,0,null,false),
            new HydrologyFlowGraphV2.Node(OUT,2,null,true));
        var flow=HydrologyFlowGraphV2.solve(nodes);
        return new HydrologySeasonalGlobalRoutingV2.Result(
            flow,HydrologyConservationV2.verify(nodes,flow),
            Map.of(),Map.of(),Map.of(OUT,2.0/86400),
            Map.of(A,a/86400,B,0.0),86400);
    }
    private static HydrologySeasonalLaggedLakeNetworkV2.Lake lake(double volume) {
        return new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2),
            volume,0,B,1);
    }
    private static HydrologySeasonalLakeCarryoverStateV2.State initial(
            Map<GlobalHydrologyAddressV2.Sample,Double> pending) {
        return new HydrologySeasonalLakeCarryoverStateV2.State(
            "world: 🌊",0,Map.of(A,0.0),pending);
    }

    @Test void canonicalCodecRoundtripsRegardlessOfInsertionOrder() {
        var forward=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        forward.put(A,2.0);
        forward.put(B,7.0);
        var reverse=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        reverse.put(B,7.0);
        reverse.put(A,2.0);
        var first=new HydrologySeasonalLakeCarryoverStateV2.State(
            "world: 🌊",8,Map.of(A,2.0),forward);
        var second=new HydrologySeasonalLakeCarryoverStateV2.State(
            "world: 🌊",8,Map.of(A,2.0),reverse);
        var encoded=HydrologySeasonalLakeCarryoverStateV2.encode(first);
        assertEquals(encoded,HydrologySeasonalLakeCarryoverStateV2.encode(second));
        assertEquals(first,HydrologySeasonalLakeCarryoverStateV2.decode(encoded));
        assertThrows(UnsupportedOperationException.class,() ->
            first.pendingM3().put(B,9.0));
    }

    @Test void checkpointAdvanceConsumesExactlyThePersistedCarryover() {
        var previous=initial(Map.of(A,9.0));
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        var next=HydrologySeasonalLakeCarryoverStateV2.advance(
            previous,forcing,candidate);
        assertEquals(1,next.season());
        assertEquals(2,next.lakeStorageM3().get(A),1e-12);
        assertEquals(7,next.pendingM3().get(B),1e-12);
        assertEquals(next,HydrologySeasonalLakeCarryoverStateV2.decode(
            HydrologySeasonalLakeCarryoverStateV2.encode(next)));
        // The old candidate cannot consume the new season's different pending water.
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.advance(
                next,Map.of(A,lake(2)),candidate));
    }

    @Test void secondSeasonPreservesUnresolvedFrontierWithoutRecreatingRain() {
        var previous=initial(Map.of(A,9.0));
        var firstForcing=Map.of(A,lake(0));
        var first=HydrologySeasonalLakeCarryoverStateV2.advance(previous,firstForcing,
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
                routed(0),firstForcing,3,previous.pendingM3()));
        var nextForcing=Map.of(A,lake(2));
        var second=HydrologySeasonalLakeCarryoverStateV2.advance(first,nextForcing,
            HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
                routed(0),nextForcing,3,first.pendingM3()));
        assertEquals(2,second.season());
        assertEquals(7,second.pendingM3().get(B),1e-12);
        assertEquals(2,second.lakeStorageM3().get(A),1e-12);
    }

    @Test void rejectsStaleForcingAndMissingCarryover() {
        var previous=initial(Map.of(A,9.0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),Map.of(A,lake(0)),3,Map.of());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.advance(
                previous,Map.of(A,lake(0)),candidate));
        var good=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),Map.of(A,lake(0)),3,previous.pendingM3());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.advance(
                previous,Map.of(A,lake(1)),good));
    }

    @Test void rejectsCrossResolutionAliasesAndTamperedPayloads() {
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,2,2);
        assertThrows(IllegalArgumentException.class,() ->
            new HydrologySeasonalLakeCarryoverStateV2.State(
                "world",0,Map.of(A,1.0,alias,2.0),Map.of()));
        assertThrows(IllegalArgumentException.class,() ->
            new HydrologySeasonalLakeCarryoverStateV2.State(
                "world",0,Map.of(A,1.0),Map.of(alias,2.0)));
        var valid=HydrologySeasonalLakeCarryoverStateV2.encode(initial(Map.of()));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.decode(valid.replace(
                "WD-LAKE-CARRYOVER-1","WD-LAKE-CARRYOVER-0")));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.decode(valid+"\n"));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.decode(valid.replace(
                "0x0.0p0","NaN")));
    }

    @Test void atomicWorldAndInboxCheckpointRejectsStaleSeasonReplay() throws IOException {
        var state=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(state),inbox);
        var file=directory.resolve("season.chk");
        HydrologyCheckpointStoreV2.save(file,checkpoint);
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,state.pendingM3());
        var next=HydrologySeasonalLakeCarryoverStateV2.advance(state,forcing,candidate);
        var nextCheckpoint=HydrologyCheckpointV2.capture(1,
            HydrologySeasonalLakeCarryoverStateV2.encode(next),inbox);
        HydrologyCheckpointStoreV2.saveNext(file,checkpoint,nextCheckpoint);
        var recovered=HydrologyCheckpointStoreV2.load(file);
        assertEquals(next,HydrologySeasonalLakeCarryoverStateV2.decode(
            recovered.worldState()));
        assertThrows(IOException.class,() ->
            HydrologyCheckpointStoreV2.saveNext(file,checkpoint,nextCheckpoint));
    }
    @Test void commitNextAtomicallyPersistsLakeAndPendingWater() throws IOException {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var file=directory.resolve("committed-lake.chk");
        HydrologyCheckpointStoreV2.save(file,checkpoint);
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        var next=HydrologySeasonalLakeCarryoverStateV2.commitNext(
            file,checkpoint,forcing,candidate);
        var loaded=HydrologyCheckpointStoreV2.load(file);
        assertEquals(1,loaded.epoch());
        assertEquals(next,HydrologySeasonalLakeCarryoverStateV2.decode(
            loaded.worldState()));
        assertEquals(checkpoint.inboxState(),loaded.inboxState());
        assertThrows(IOException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.commitNext(
                file,checkpoint,forcing,candidate));
    }

    @Test void commitNextRejectsMismatchedCheckpointEpochBeforeWriting() throws IOException {
        var previous=initial(Map.of(A,9.0));
        var checkpoint=HydrologyCheckpointV2.capture(7,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),
            new HydrologyEpochInboxV2());
        var file=directory.resolve("wrong-epoch.chk");
        HydrologyCheckpointStoreV2.save(file,checkpoint);
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.commitNext(
                file,checkpoint,forcing,candidate));
        assertEquals(checkpoint,HydrologyCheckpointStoreV2.load(file));
    }

    @Test void incomingReceiptChangeBlocksStaleLakeSeasonCommit() throws IOException {
        var previous=initial(Map.of(A,9.0));
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),
            new HydrologyEpochInboxV2());
        var file=directory.resolve("incoming-water.chk");
        HydrologyCheckpointStoreV2.save(file,checkpoint);
        var inbox=HydrologyCheckpointV2.restoreInbox(checkpoint);
        inbox.receive(new HydrologyTransferLedgerV2.Key(2,A,B),3);
        var appended=HydrologyCheckpointV2.capture(0,
            checkpoint.worldState(),inbox);
        HydrologyCheckpointStoreV2.saveIncoming(file,appended);
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        assertThrows(IOException.class,() ->
            HydrologySeasonalLakeCarryoverStateV2.commitNext(
                file,checkpoint,forcing,candidate));
        assertEquals(appended,HydrologyCheckpointStoreV2.load(file));
    }


    @Test void receiptAndLakeWaterCommitAtomicallyWithoutDoubleCounting() throws IOException {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        var key=new HydrologyTransferLedgerV2.Key(0,A,B);
        inbox.receive(key,3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var file=directory.resolve("lake-with-inbox.chk");
        HydrologyCheckpointStoreV2.save(file,expected);
        var forcing=Map.of(A,lake(0));
        var carryover=HydrologySeasonalLakeInboxCommitV2.combined(
            previous.pendingM3(),Map.of(B,3.0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,carryover);
        var prepared=HydrologySeasonalLakeInboxCommitV2.commitNext(
            file,expected,forcing,candidate,Set.of(B));
        assertEquals(Map.of(B,3.0),prepared.claimedM3());
        assertEquals(10.0,prepared.state().pendingM3().get(B),1e-12);
        var recovered=HydrologyCheckpointStoreV2.load(file);
        assertEquals(prepared.checkpoint(),recovered);
        var receipts=HydrologyCheckpointV2.restoreInbox(recovered).snapshot();
        assertTrue(receipts.delivered().contains(key));
        assertTrue(receipts.consumedEpochs().contains(0L));
        assertThrows(IOException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.commitNext(
                file,expected,forcing,candidate,Set.of(B)));
    }

    @Test void unloadedReceiptSurvivesSeasonAndIsClaimedWhenLoaded() throws IOException {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        var key=new HydrologyTransferLedgerV2.Key(0,A,B);
        inbox.receive(key,3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var file=directory.resolve("unloaded-receipt.chk");
        HydrologyCheckpointStoreV2.save(file,expected);
        var firstForcing=Map.of(A,lake(0));
        var firstCandidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),firstForcing,3,previous.pendingM3());
        var first=HydrologySeasonalLakeInboxCommitV2.commitNext(
            file,expected,firstForcing,firstCandidate,Set.of(A));
        assertTrue(first.claimedM3().isEmpty());
        assertFalse(HydrologyCheckpointV2.restoreInbox(first.checkpoint())
            .snapshot().delivered().contains(key));
        var secondForcing=Map.of(A,lake(2));
        var secondCarryover=HydrologySeasonalLakeInboxCommitV2.combined(
            first.state().pendingM3(),Map.of(B,3.0));
        var secondCandidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),secondForcing,3,secondCarryover);
        var second=HydrologySeasonalLakeInboxCommitV2.commitNext(
            file,first.checkpoint(),secondForcing,secondCandidate,Set.of(B));
        assertEquals(Map.of(B,3.0),second.claimedM3());
        assertEquals(10.0,second.state().pendingM3().get(B),1e-12);
        assertTrue(HydrologyCheckpointV2.restoreInbox(second.checkpoint())
            .snapshot().delivered().contains(key));
    }

    @Test void candidateMustIncludeExactlyClaimedReceipts() {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,A,B),3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var forcing=Map.of(A,lake(0));
        var missing=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.prepare(
                expected,forcing,missing,Set.of(B)));
    }

    @Test void futureReceiptsAreNotConsumedEarly() {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        var future=new HydrologyTransferLedgerV2.Key(2,A,B);
        inbox.receive(future,3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        var prepared=HydrologySeasonalLakeInboxCommitV2.prepare(
            expected,forcing,candidate,Set.of(B));
        assertTrue(prepared.claimedM3().isEmpty());
        assertFalse(HydrologyCheckpointV2.restoreInbox(prepared.checkpoint())
            .snapshot().delivered().contains(future));
    }

    @Test void crossResolutionReceiptAliasIsRejectedBeforeWrite() throws IOException {
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,2,2);
        var neighbor=new GlobalHydrologyAddressV2.Sample(0,4,3,2);
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,neighbor,alias),3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        var file=directory.resolve("aliased-receipt.chk");
        HydrologyCheckpointStoreV2.save(file,expected);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.commitNext(
                file,expected,forcing,candidate,Set.of(alias)));
        assertEquals(expected,HydrologyCheckpointStoreV2.load(file));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.prepare(
                expected,forcing,candidate,Set.of(A,alias)));
    }

    @Test void staleCheckpointWithNewReceiptsCannotEraseInbox() throws IOException {
        var previous=initial(Map.of(A,9.0));
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),
            new HydrologyEpochInboxV2());
        var file=directory.resolve("stale-receipt.chk");
        HydrologyCheckpointStoreV2.save(file,expected);
        var forcing=Map.of(A,lake(0));
        var candidate=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(0),forcing,3,previous.pendingM3());
        var inbox=HydrologyCheckpointV2.restoreInbox(expected);
        inbox.receive(new HydrologyTransferLedgerV2.Key(2,A,B),3);
        var updated=HydrologyCheckpointV2.capture(0,expected.worldState(),inbox);
        HydrologyCheckpointStoreV2.saveIncoming(file,updated);
        assertThrows(IOException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.commitNext(
                file,expected,forcing,candidate,Set.of(B)));
        assertEquals(updated,HydrologyCheckpointStoreV2.load(file));
    }

    @Test void mergedReceiptVolumeOverflowIsRejected() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.combined(
                Map.of(B,Double.MAX_VALUE),Map.of(B,Double.MAX_VALUE)));
        var first=HydrologySeasonalLakeInboxCommitV2.combined(
            Map.of(A,1.0,B,2.0),Map.of(B,3.0));
        var second=HydrologySeasonalLakeInboxCommitV2.combined(
            Map.of(B,2.0,A,1.0),Map.of(B,3.0));
        assertEquals(first,second);
        assertEquals(5.0,first.get(B),1e-12);
    }


    @Test void uniformRunnerComputesAndCommitsReceiptCarryoverWithoutCallerDuplication()
            throws IOException {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,A,B),3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var file=directory.resolve("uniform-runner.chk");
        HydrologyCheckpointStoreV2.save(file,expected);
        var forcing=Map.of(A,lake(0));
        var planned=HydrologySeasonalLakeInboxCommitV2.prepareUniform(
            expected,routed(0),forcing,3,Set.of(B));
        assertEquals(expected,HydrologyCheckpointStoreV2.load(file));
        var committed=HydrologySeasonalLakeInboxCommitV2.commitUniform(
            file,expected,routed(0),forcing,3,Set.of(B));
        assertEquals(planned,committed);
        assertEquals(10.0,committed.state().pendingM3().get(B),1e-12);
        assertEquals(committed.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

    @Test void scheduledRunnerCommitsReceiptsWithChronologicalLakeForcing()
            throws IOException {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,A,B),3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var file=directory.resolve("scheduled-runner.chk");
        HydrologyCheckpointStoreV2.save(file,expected);
        var forcing=Map.of(A,lake(0));
        var intervals=List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0));
        var scheduled=HydrologySeasonalLakeInboxCommitV2.commitScheduled(
            file,expected,routed(0),forcing,Map.of(A,intervals),Set.of(B));
        assertEquals(10.0,scheduled.state().pendingM3().get(B),1e-12);
        assertEquals(scheduled.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

    @Test void invalidScheduleCannotCommitOrConsumeReceipt() throws IOException {
        var previous=initial(Map.of(A,9.0));
        var inbox=new HydrologyEpochInboxV2();
        var key=new HydrologyTransferLedgerV2.Key(0,A,B);
        inbox.receive(key,3);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
        var file=directory.resolve("invalid-schedule.chk");
        HydrologyCheckpointStoreV2.save(file,expected);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.commitScheduled(
                file,expected,routed(0),Map.of(A,lake(0)),
                Map.of(A,List.of(new HydrologySeasonalLakeIntegratorV2.Interval(2,0))),
                Set.of(B)));
        assertEquals(expected,HydrologyCheckpointStoreV2.load(file));
        assertFalse(HydrologyCheckpointV2.restoreInbox(
            HydrologyCheckpointStoreV2.load(file)).snapshot().delivered().contains(key));
    }

}
