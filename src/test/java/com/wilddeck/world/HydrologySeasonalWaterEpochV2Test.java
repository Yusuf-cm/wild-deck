package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalWaterEpochV2Test {
    @TempDir Path directory;
    private static final GlobalHydrologyAddressV2.Sample A=at(2);
    private static final GlobalHydrologyAddressV2.Sample B=at(3);
    private static final GlobalHydrologyAddressV2.Sample C=at(4);
    private static final GlobalHydrologyAddressV2.Sample M=at(5);
    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,2);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample> edges() {
        var edges=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        edges.put(B,C); edges.put(C,M); edges.put(M,null);
        return edges;
    }
    private static Set<GlobalHydrologyAddressV2.Sample> loaded() {
        return Set.of(A,B,C,M);
    }
    private static HydrologySeasonalGlobalRoutingV2.Result runoff(double atLake,double atMouth) {
        var nodes=List.of(new HydrologyFlowGraphV2.Node(A,atLake,null,false),
            new HydrologyFlowGraphV2.Node(M,atMouth,null,true));
        var flow=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,flow);
        return new HydrologySeasonalGlobalRoutingV2.Result(
            flow,budget,Map.of(),Map.of(),Map.of(M,atMouth/86400.0),
            Map.of(A,atLake/86400.0),86400.0);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
            HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes(double previous) {
        return Map.of(A,new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2),
            previous,0,B,1));
    }
    private static HydrologyCheckpointV2.Checkpoint initial(double pending,double receipt) {
        var inbox=new HydrologyEpochInboxV2();
        if (receipt>0) inbox.receive(new HydrologyTransferLedgerV2.Key(0,B,C),receipt);
        var old=new HydrologySeasonalLakeCarryoverStateV2.State(
            "world",0,Map.of(A,0.0),pending==0?Map.of():Map.of(B,pending));
        return HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(old),inbox);
    }
    private static HydrologySeasonalWaterEpochV2.Prepared advance(
            HydrologyCheckpointV2.Checkpoint checkpoint,double rain,double prior) {
        return HydrologySeasonalWaterEpochV2.prepareUniform(
            checkpoint,runoff(rain,2),lakes(prior),2,edges(),loaded(),Set.of(M));
    }

    @Test void oneEpochCombinesOldWaterReceiptsLakeStorageAndTwoOceanSources() {
        var first=initial(3,4);
        var result=advance(first,5,0);
        assertEquals(Map.of(M,9.0),result.state().dischargedM3());
        assertEquals(Map.of(M,9.0),result.newOceanDischargeM3());
        assertEquals(Map.of(B,3.0),result.state().lakes().pendingM3());
        assertEquals(2.0,result.state().lakes().lakeStorageM3().get(A),1e-12);
        assertEquals(Map.of(C,4.0),result.claimedM3());
        assertEquals(7.0,result.oldWaterRouting().outletTotalM3(),1e-12);
        assertEquals(7.0,result.lakeStep().result().seasonalRunoffM3(),1e-12);
        assertTrue(HydrologyCheckpointV2.restoreInbox(result.checkpoint()).snapshot()
            .delivered().contains(new HydrologyTransferLedgerV2.Key(0,B,C)));
        assertEquals(1,result.checkpoint().epoch());
        assertEquals(result.state(),HydrologySeasonalOutletSettlementV2.decode(
            result.checkpoint().worldState()));
    }

    @Test void restartSettlesDeferredLakeSpillWithoutReplayingReceipt() throws IOException {
        var file=directory.resolve("seasonal.chk");
        var first=initial(3,4);
        HydrologyCheckpointStoreV2.save(file,first);
        var firstSeason=HydrologySeasonalWaterEpochV2.commitUniform(
            file,first,runoff(5,2),lakes(0),2,edges(),loaded(),Set.of(M));
        var restored=HydrologyCheckpointStoreV2.load(file);
        assertEquals(firstSeason.checkpoint(),restored);
        var second=HydrologySeasonalWaterEpochV2.commitUniform(
            file,restored,runoff(0,2),lakes(2),2,edges(),loaded(),Set.of(M));
        assertEquals(Map.of(M,14.0),second.state().dischargedM3());
        assertTrue(second.state().lakes().pendingM3().isEmpty());
        assertTrue(second.claimedM3().isEmpty());
        assertEquals(2.0,second.state().lakes().lakeStorageM3().get(A),1e-12);
        assertThrows(IOException.class,()->HydrologySeasonalWaterEpochV2.commitUniform(
            file,restored,runoff(0,2),lakes(2),2,edges(),loaded(),Set.of(M)));
        assertEquals(second.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

    @Test void chronologicalAndUniformLakeSchedulesMatchWhenForcingMatches() {
        var first=initial(3,4);
        var uniform=advance(first,5,0);
        var schedules=Map.of(A,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(2.5,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(2.5,0)));
        var scheduled=HydrologySeasonalWaterEpochV2.prepareScheduled(
            first,runoff(5,2),lakes(0),schedules,edges(),loaded(),Set.of(M));
        assertEquals(uniform.state(),scheduled.state());
        assertEquals(uniform.checkpoint(),scheduled.checkpoint());
        assertEquals(uniform.newOceanDischargeM3(),scheduled.newOceanDischargeM3());
    }

    @Test void refusesToAdvanceAnUnloadedLakeOrUnloadedNewOceanMouth() {
        var first=initial(3,4);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalWaterEpochV2.prepareUniform(
                first,runoff(5,2),lakes(0),2,edges(),Set.of(B,C,M),Set.of(M)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalWaterEpochV2.prepareUniform(
                first,runoff(5,2),lakes(0),2,edges(),Set.of(A,B,C),Set.of(M)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalWaterEpochV2.prepareUniform(
                first,runoff(5,2),lakes(0),2,edges(),loaded(),Set.of()));
    }

    @Test void refusesFakeMouthAndCrossResolutionSharedVertex() {
        var first=initial(3,4);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalWaterEpochV2.prepareUniform(
                first,runoff(5,2),lakes(0),2,Map.of(B,C,C,M),loaded(),Set.of(M)));
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,3,2);
        var old=new HydrologySeasonalLakeCarryoverStateV2.State(
            "world",0,Map.of(A,0.0),Map.of(B,3.0));
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,
            new GlobalHydrologyAddressV2.Sample(0,4,2,2),alias),4.0);
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(old),inbox);
        var loaded=new HashSet<>(loaded());loaded.add(alias);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalWaterEpochV2.prepareUniform(
                expected,runoff(5,2),lakes(0),2,edges(),loaded,Set.of(M)));
    }

    @Test void staleSeasonAndPrecisionLossFailBeforePersistence() throws IOException {
        var file=directory.resolve("rejected.chk");
        var first=initial(3,4);
        HydrologyCheckpointStoreV2.save(file,first);
        var stale=HydrologyCheckpointV2.capture(2,first.worldState(),
            HydrologyCheckpointV2.restoreInbox(first));
        assertThrows(IllegalArgumentException.class,()->advance(stale,5,0));
        var huge=new HydrologySeasonalOutletSettlementV2.State(
            HydrologySeasonalLakeCarryoverStateV2.decode(initial(0,0).worldState()),
            Map.of(M,1.0e16));
        var expected=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalOutletSettlementV2.encode(huge),
            HydrologyCheckpointV2.restoreInbox(initial(0,0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalWaterEpochV2.prepareUniform(expected,runoff(0,1),
                lakes(0),2,edges(),loaded(),Set.of(M)));
        assertEquals(first,HydrologyCheckpointStoreV2.load(file));
    }

    @Test void noWaterCanBeDischargedTwiceWhenNoPendingWaterRemains() {
        var first=initial(0,0);
        var result=advance(first,0,0);
        assertEquals(Map.of(M,2.0),result.state().dischargedM3());
        assertEquals(0.0,result.oldWaterRouting().outletTotalM3(),0.0);
        var next=HydrologySeasonalWaterEpochV2.prepareUniform(
            result.checkpoint(),runoff(0,0),lakes(0),2,edges(),loaded(),Set.of(M));
        assertEquals(Map.of(M,2.0),next.state().dischargedM3());
        assertEquals(0.0,next.newOceanDischargeM3().get(M),0.0);
    }

    @Test void unloadedBoundaryDefersReceiptAndOldWaterWithoutFabricatingOcean() {
        var first=initial(3,4);
        var parked=HydrologySeasonalWaterEpochV2.prepareUniform(
            first,runoff(5,2),lakes(0),2,edges(),Set.of(A,B,M),Set.of(M));
        assertEquals(Map.of(M,2.0),parked.state().dischargedM3());
        assertEquals(Map.of(B,3.0,C,3.0),parked.state().lakes().pendingM3());
        assertTrue(parked.claimedM3().isEmpty());
        assertFalse(HydrologyCheckpointV2.restoreInbox(parked.checkpoint())
            .snapshot().delivered().contains(new HydrologyTransferLedgerV2.Key(0,B,C)));
        var arrived=HydrologySeasonalWaterEpochV2.prepareUniform(
            parked.checkpoint(),runoff(0,2),lakes(2),2,edges(),loaded(),Set.of(M));
        assertEquals(Map.of(M,14.0),arrived.state().dischargedM3());
        assertTrue(arrived.state().lakes().pendingM3().isEmpty());
        assertEquals(Map.of(C,4.0),arrived.claimedM3());
    }

    @Test void priorLakeInletWaterIsNotRecountedAsRain() {
        var inbox=new HydrologyEpochInboxV2();
        var old=new HydrologySeasonalLakeCarryoverStateV2.State(
            "world",0,Map.of(A,0.0),Map.of(A,4.0));
        var first=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(old),inbox);
        var next=HydrologySeasonalWaterEpochV2.prepareUniform(
            first,runoff(5,2),lakes(0),2,edges(),loaded(),Set.of(M));
        assertEquals(9.0,next.lakeStep().result().lakeInflowM3().get(A),1e-12);
        assertEquals(5.0,next.lakeStep().result().seasonalRunoffM3()-2.0,1e-12);
        assertEquals(Map.of(M,2.0),next.state().dischargedM3());
        assertEquals(Map.of(B,7.0),next.state().lakes().pendingM3());
    }

    @Test void staleReceiptAppendCannotBeSilentlyLostBySeasonalCommit() throws IOException {
        var file=directory.resolve("race.chk");
        var first=initial(3,0);
        HydrologyCheckpointStoreV2.save(file,first);
        var inbox=HydrologyCheckpointV2.restoreInbox(first);
        inbox.receive(new HydrologyTransferLedgerV2.Key(2,B,C),4.0);
        var appended=HydrologyCheckpointV2.capture(0,first.worldState(),inbox);
        HydrologyCheckpointStoreV2.saveIncoming(file,appended);
        assertThrows(IOException.class,()->
            HydrologySeasonalWaterEpochV2.commitUniform(
                file,first,runoff(5,2),lakes(0),2,edges(),loaded(),Set.of(M)));
        assertEquals(appended,HydrologyCheckpointStoreV2.load(file));
    }
}
