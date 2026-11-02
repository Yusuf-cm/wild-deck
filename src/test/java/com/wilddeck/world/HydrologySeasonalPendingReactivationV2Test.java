package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalPendingReactivationV2Test {
    @TempDir Path directory;
    private static final GlobalHydrologyAddressV2.Sample A=at(2,2);
    private static final GlobalHydrologyAddressV2.Sample B=at(3,2);
    private static final GlobalHydrologyAddressV2.Sample C=at(4,2);
    private static final GlobalHydrologyAddressV2.Sample D=at(5,2);
    private static final GlobalHydrologyAddressV2.Sample OUT=at(6,2);
    private static final GlobalHydrologyAddressV2.Sample SPILL=at(2,3);

    private static GlobalHydrologyAddressV2.Sample at(int x,int y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample> routes() {
        return Map.of(C,B,B,A);
    }
    private static HydrologySeasonalGlobalRoutingV2.Result routed() {
        var nodes=List.of(new HydrologyFlowGraphV2.Node(A,0,null,false),
            new HydrologyFlowGraphV2.Node(B,0,null,false),
            new HydrologyFlowGraphV2.Node(OUT,2,null,true));
        var solved=HydrologyFlowGraphV2.solve(nodes);
        return new HydrologySeasonalGlobalRoutingV2.Result(
            solved,HydrologyConservationV2.verify(nodes,solved),
            Map.of(),Map.of(),Map.of(OUT,2.0/86400),
            Map.of(A,0.0,B,0.0),86400);
    }
    private static HydrologySeasonalLaggedLakeNetworkV2.Lake lake(double previous) {
        return new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2),
            previous,0,SPILL,1);
    }
    private static HydrologyCheckpointV2.Checkpoint checkpoint(boolean receipt) {
        var previous=new HydrologySeasonalLakeCarryoverStateV2.State(
            "reactivated-world",0,Map.of(A,0.0),Map.of(C,5.0));
        var inbox=new HydrologyEpochInboxV2();
        if (receipt)
            inbox.receive(new HydrologyTransferLedgerV2.Key(0,D,C),3);
        return HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(previous),inbox);
    }
    private static Set<GlobalHydrologyAddressV2.Sample> loaded() {
        return Set.of(A,B,C);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> move(
            Map<GlobalHydrologyAddressV2.Sample,Double> pending,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            Map<GlobalHydrologyAddressV2.Sample,
                GlobalHydrologyAddressV2.Sample> routes) {
        return HydrologySeasonalPendingReactivationV2.reactivate(
            pending,routes,loaded,Set.of(A),Set.of(OUT));
    }

    @Test void multipleLoadedHopsAndConfluenceCountWaterOnce() {
        var graph=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        graph.put(D,C);graph.put(C,B);graph.put(B,A);
        var first=move(Map.of(C,5.0,D,3.0),Set.of(A,B,C,D),graph);
        var reverse=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        reverse.put(D,3.0);reverse.put(C,5.0);
        assertEquals(Map.of(A,8.0),first);
        assertEquals(first,move(reverse,Set.of(D,C,B,A),graph));
        assertThrows(UnsupportedOperationException.class,()->first.put(A,9.0));
    }

    @Test void knownEdgeIntoUnloadedRegionParksWaterWithoutInventingFlow() {
        assertEquals(Map.of(B,5.0),move(Map.of(C,5.0),Set.of(C),routes()));
        assertEquals(Map.of(C,5.0),move(Map.of(C,5.0),Set.of(),routes()));
        assertEquals(Map.of(A,5.0),move(Map.of(C,5.0),loaded(),routes()));
    }

    @Test void rejectsCyclesAndNonadjacentOrUntrustedOceanRoutes() {
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,5.0),Set.of(B,C),Map.of(C,B,B,C)));
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,5.0),Set.of(C),Map.of(C,A)));
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(D,5.0),Set.of(D),Map.of(D,OUT)));
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(OUT,5.0),Set.of(),Map.of()));
    }

    @Test void rejectsSharedVertexAliasesAndInvalidVolumes() {
        var alias=new GlobalHydrologyAddressV2.Sample(0,4,4,2);
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,5.0,alias,2.0),loaded(),routes()));
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,5.0),Set.of(C,alias),routes()));
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,-1.0),loaded(),routes()));
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,Double.NaN),loaded(),routes()));
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,Double.POSITIVE_INFINITY),loaded(),routes()));
    }

    @Test void overflowFailsBeforeAnyCheckpointMutation() {
        var graph=Map.of(D,C,C,B,B,A);
        assertThrows(IllegalArgumentException.class,()->move(
            Map.of(C,Double.MAX_VALUE,D,Double.MAX_VALUE),
            Set.of(A,B,C,D),graph));
    }

    @Test void uniformReactivationConsumesReceiptsAndPersistsConservedLakeWater()
            throws IOException {
        var before=checkpoint(true);
        var file=directory.resolve("reactivated.chk");
        HydrologyCheckpointStoreV2.save(file,before);
        var forcing=Map.of(A,lake(0));
        var planned=HydrologySeasonalLakeInboxCommitV2.prepareUniformReactivated(
            before,routed(),forcing,3,loaded(),routes());
        assertEquals(Map.of(C,3.0),planned.claimedM3());
        assertEquals(2,planned.state().lakeStorageM3().get(A),1e-12);
        assertEquals(6,planned.state().pendingM3().get(SPILL),1e-12);
        var committed=HydrologySeasonalLakeInboxCommitV2.commitUniformReactivated(
            file,before,routed(),forcing,3,loaded(),routes());
        assertEquals(planned,committed);
        assertEquals(planned.checkpoint(),HydrologyCheckpointStoreV2.load(file));
        var key=new HydrologyTransferLedgerV2.Key(0,D,C);
        assertTrue(HydrologyCheckpointV2.restoreInbox(committed.checkpoint())
            .snapshot().delivered().contains(key));
        assertThrows(IOException.class,()->HydrologySeasonalLakeInboxCommitV2
            .commitUniformReactivated(file,before,routed(),forcing,3,loaded(),routes()));
    }

    @Test void scheduledReactivationMatchesUniformWithEqualIntervals()
            throws IOException {
        var before=checkpoint(true);
        var file=directory.resolve("scheduled.chk");
        HydrologyCheckpointStoreV2.save(file,before);
        var forcing=Map.of(A,lake(0));
        var intervals=Map.of(A,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(0,0)));
        var uniform=HydrologySeasonalLakeInboxCommitV2.prepareUniformReactivated(
            before,routed(),forcing,3,loaded(),routes());
        var scheduled=HydrologySeasonalLakeInboxCommitV2.prepareScheduledReactivated(
            before,routed(),forcing,intervals,loaded(),routes());
        assertEquals(uniform,scheduled);
        assertEquals(scheduled,HydrologySeasonalLakeInboxCommitV2
            .commitScheduledReactivated(file,before,routed(),forcing,
                intervals,loaded(),routes()));
    }

    @Test void omittedOrDuplicatedRelocatedWaterFailsPreflight() {
        var before=checkpoint(true);
        var forcing=Map.of(A,lake(0));
        var wrong=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(),forcing,3,Map.of(C,8.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalLakeInboxCommitV2.prepareReactivated(
                before,forcing,wrong,loaded(),routes(),Set.of(OUT)));
        var correct=HydrologySeasonalLaggedLakeNetworkV2.advanceUniformWithCarryover(
            routed(),forcing,3,Map.of(A,8.0));
        assertEquals(2,HydrologySeasonalLakeInboxCommitV2.prepareReactivated(
            before,forcing,correct,loaded(),routes(),Set.of(OUT))
            .state().lakeStorageM3().get(A),1e-12);
    }

    @Test void unloadingIntermediateVertexKeepsWaterPendingForLaterSeason() {
        var before=checkpoint(false);
        var forcing=Map.of(A,lake(0));
        var prepared=HydrologySeasonalLakeInboxCommitV2.prepareUniformReactivated(
            before,routed(),forcing,2,Set.of(A,C),routes());
        assertEquals(5,prepared.state().pendingM3().get(B),1e-12);
        assertEquals(0,prepared.state().lakeStorageM3().get(A),1e-12);
    }
}
