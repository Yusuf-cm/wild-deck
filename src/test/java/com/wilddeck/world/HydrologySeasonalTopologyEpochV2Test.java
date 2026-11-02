package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalTopologyEpochV2Test {
    @TempDir Path directory;
    private static final GlobalHydrologyAddressV2.Sample M=at(5,2);
    private static final GlobalHydrologyAddressV2.Sample B=at(3,2);
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> planet() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return sources;
    }
    private static HydrologySphericalRouteAtlasV2 atlas() {
        return HydrologySphericalRouteAtlasV2.compile(
            planet(),a->a.equals(M)?0:10,Set.of(M));
    }
    private static HydrologySphericalRouteAtlasV2 changedAtlas() {
        var other=at(0,2);
        return HydrologySphericalRouteAtlasV2.compile(
            planet(),a->a.equals(M)||a.equals(other)?0:10,Set.of(M,other));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>
            edges(HydrologySphericalRouteAtlasV2 atlas) {
        var edges=new HashMap<GlobalHydrologyAddressV2.Sample,GlobalHydrologyAddressV2.Sample>();
        for(var node:atlas.routes()) edges.put(node.address(),node.downstream());
        return edges;
    }
    private static Set<GlobalHydrologyAddressV2.Sample>
            loaded(HydrologySphericalRouteAtlasV2 atlas) {
        var result=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:atlas.routes()) result.add(node.address());
        return result;
    }
    private static HydrologySeasonalGlobalRoutingV2.Result runoff(double amount) {
        var nodes=List.of(new HydrologyFlowGraphV2.Node(M,amount,null,true));
        var flow=HydrologyFlowGraphV2.solve(nodes);
        var budget=HydrologyConservationV2.verify(nodes,flow);
        return new HydrologySeasonalGlobalRoutingV2.Result(
            flow,budget,Map.of(),Map.of(),Map.of(M,amount/86400.0),
            Map.of(),86400.0);
    }
    private static HydrologyCheckpointV2.Checkpoint fresh() {
        return HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(),Map.of())),new HydrologyEpochInboxV2());
    }
    private static HydrologyCheckpointV2.Checkpoint bound(HydrologySphericalRouteAtlasV2 atlas) {
        return HydrologySeasonalTopologyEpochV2.bindFresh(fresh(),atlas);
    }
    private static HydrologySeasonalTopologyEpochV2.Prepared advance(
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,double rain,
            Set<GlobalHydrologyAddressV2.Sample> loaded) {
        return HydrologySeasonalTopologyEpochV2.prepareUniform(
            expected,atlas,runoff(rain),Map.of(),2,edges(atlas),loaded,Set.of(M));
    }

    @Test void boundSeasonalEpochPreservesAtlasIdentityAndConservesOceanDischarge() {
        var atlas=atlas();
        var first=bound(atlas);
        var result=advance(first,atlas,2,loaded(atlas));
        assertEquals(1,result.checkpoint().epoch());
        assertEquals(Map.of(M,2.0),result.water().state().dischargedM3());
        assertEquals(HydrologyDrainageSnapshotIdV2.of(atlas),result.snapshotId());
        assertEquals(result.snapshotId(),
            HydrologySeasonalTopologyEpochV2.decode(
                result.checkpoint().worldState()).snapshotId());
        assertEquals(Map.of(M,2.0),HydrologySeasonalOutletSettlementV2.decode(
            HydrologySeasonalTopologyEpochV2.decode(
                result.checkpoint().worldState()).waterState()).dischargedM3());
        var second=advance(result.checkpoint(),atlas,0,loaded(atlas));
        assertEquals(Map.of(M,2.0),second.water().state().dischargedM3());
        assertTrue(second.water().state().lakes().pendingM3().isEmpty());
    }

    @Test void receiptRoutesToVerifiedMouthOnceAcrossRestart() throws IOException {
        var atlas=atlas();
        var inbox=new HydrologyEpochInboxV2();
        var key=new HydrologyTransferLedgerV2.Key(0,at(2,2),B);
        inbox.receive(key,3.0);
        var first=HydrologyCheckpointV2.capture(0,bound(atlas).worldState(),inbox);
        var file=directory.resolve("topology-water.chk");
        HydrologyCheckpointStoreV2.save(file,first);
        var one=HydrologySeasonalTopologyEpochV2.commitUniform(
            file,first,atlas,runoff(2),Map.of(),2,edges(atlas),
            loaded(atlas),Set.of(M));
        assertEquals(Map.of(M,5.0),one.water().state().dischargedM3());
        assertEquals(Map.of(B,3.0),one.water().claimedM3());
        var recovered=HydrologyCheckpointStoreV2.load(file);
        assertEquals(one.checkpoint(),recovered);
        var two=HydrologySeasonalTopologyEpochV2.commitUniform(
            file,recovered,atlas,runoff(0),Map.of(),2,edges(atlas),
            loaded(atlas),Set.of(M));
        assertEquals(Map.of(M,5.0),two.water().state().dischargedM3());
        assertTrue(two.water().claimedM3().isEmpty());
        assertThrows(IOException.class,()->HydrologySeasonalTopologyEpochV2.commitUniform(
            file,recovered,atlas,runoff(0),Map.of(),2,edges(atlas),
            loaded(atlas),Set.of(M)));
        assertEquals(two.checkpoint(),HydrologyCheckpointStoreV2.load(file));
    }

    @Test void unloadedMouthRetainsReceiptUntilRegionLoads() {
        var atlas=atlas();
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,at(2,2),B),3.0);
        var first=HydrologyCheckpointV2.capture(0,bound(atlas).worldState(),inbox);
        var partialRunoff=HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
            atlas,Map.of(B,0.0),Set.of(B),86400);
        var partial=HydrologySeasonalTopologyEpochV2.prepareUniform(
            first,atlas,partialRunoff,Map.of(),2,
            edges(atlas),Set.of(B),Set.of(M));
        assertEquals(3.0,partial.water().oldWaterRouting().inputM3(),1e-12);
        assertEquals(0.0,partial.water().oldWaterRouting().outletTotalM3(),0.0);
        assertEquals(3.0,partial.water().state().lakes().pendingM3()
            .values().stream().mapToDouble(Double::doubleValue).sum(),1e-12);
        var full=advance(partial.checkpoint(),atlas,0,loaded(atlas));
        assertEquals(Map.of(M,3.0),full.water().state().dischargedM3());
        assertTrue(full.water().claimedM3().isEmpty());
    }

    @Test void rejectsChangedAtlasBeforeClaimingReceiptOrWriting() throws IOException {
        var atlas=atlas();
        var first=bound(atlas);
        var file=directory.resolve("topology-race.chk");
        HydrologyCheckpointStoreV2.save(file,first);
        assertNotEquals(HydrologyDrainageSnapshotIdV2.of(atlas),
            HydrologyDrainageSnapshotIdV2.of(changedAtlas()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.commitUniform(
                file,first,changedAtlas(),runoff(2),Map.of(),2,
                edges(atlas),loaded(atlas),Set.of(M)));
        assertEquals(first,HydrologyCheckpointStoreV2.load(file));
    }

    @Test void rejectsForgedEdgesEvenWhenNoPendingWater() {
        var atlas=atlas();
        var forged=edges(atlas);
        forged.put(B,at(0,2));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.prepareUniform(
                bound(atlas),atlas,runoff(0),Map.of(),2,
                forged,loaded(atlas),Set.of(M)));
    }

    @Test void rejectsInventedOceanMouthAndForeignSeasonalOutlet() {
        var atlas=atlas();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.prepareUniform(
                bound(atlas),atlas,runoff(0),Map.of(),2,
                edges(atlas),loaded(atlas),Set.of(B,M)));
        var falseFlow=HydrologyFlowGraphV2.solve(
            List.of(new HydrologyFlowGraphV2.Node(B,1,null,true)));
        var falseRunoff=new HydrologySeasonalGlobalRoutingV2.Result(
            falseFlow,HydrologyConservationV2.verify(
                List.of(new HydrologyFlowGraphV2.Node(B,1,null,true)),falseFlow),
            Map.of(),Map.of(),Map.of(B,1.0/86400.0),Map.of(),86400.0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.prepareUniform(
                bound(atlas),atlas,falseRunoff,Map.of(),2,
                edges(atlas),loaded(atlas),Set.of(M)));
    }

    @Test void rejectsCrossResolutionLoadedAlias() {
        var atlas=atlas();
        var loaded=loaded(atlas);
        loaded.add(new GlobalHydrologyAddressV2.Sample(0,4,3,2));
        assertThrows(IllegalArgumentException.class,()->
            advance(bound(atlas),atlas,0,loaded));
    }

    @Test void freshBindingRejectsPendingWaterAndUnsettledReceipts() {
        var atlas=atlas();
        var pending=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(),Map.of(B,1.0))),new HydrologyEpochInboxV2());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.bindFresh(pending,atlas));
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(0,at(2,2),B),3.0);
        var receipt=HydrologyCheckpointV2.capture(0,fresh().worldState(),inbox);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.bindFresh(receipt,atlas));
    }

    @Test void scheduledAndUniformEpochsAgreeWithoutLakes() {
        var atlas=atlas();
        var first=bound(atlas);
        var uniform=advance(first,atlas,2,loaded(atlas));
        var scheduled=HydrologySeasonalTopologyEpochV2.prepareScheduled(
            first,atlas,runoff(2),Map.of(),Map.of(),edges(atlas),
            loaded(atlas),Set.of(M));
        assertEquals(uniform.checkpoint(),scheduled.checkpoint());
        assertEquals(uniform.water().state(),scheduled.water().state());
    }

    @Test void codecRoundTripRejectsTamperingAndUnboundLegacyPayload() {
        var atlas=atlas();
        var checkpoint=bound(atlas);
        var payload=checkpoint.worldState();
        assertEquals(payload,HydrologySeasonalTopologyEpochV2.encode(
            HydrologySeasonalTopologyEpochV2.decode(payload)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.decode(payload+"\n"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.decode(fresh().worldState()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.decode(payload.replace(
                HydrologyDrainageSnapshotIdV2.of(atlas),"not-a-hash")));
    }
    @Test void atlasOwnedRunoffRoutesAcrossRegionsWithoutDuplicateRainfall() {
        var atlas=atlas();
        var sources=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:atlas.routes()) sources.put(node.address(),0.0);
        sources.put(B,3.0);
        sources.put(M,2.0);
        var routed=HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
            atlas,sources,loaded(atlas),86400);
        assertEquals(Map.of(M,5.0),routed.drainage().terminalOutflow());
        assertTrue(routed.drainage().pendingOutflow().isEmpty());
        assertEquals(5.0,routed.budget().input(),1e-12);
        assertEquals(HydrologyGlobalCatchmentsV2.coordinate(M),
            routed.catchments().get(B).mouth());
        var prepared=HydrologySeasonalTopologyEpochV2.prepareUniform(
            bound(atlas),atlas,routed,Map.of(),2,
            edges(atlas),loaded(atlas),Set.of(M));
        assertEquals(Map.of(M,5.0),prepared.water().state().dischargedM3());
        var reversed=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        var addresses=new ArrayList<>(sources.keySet());
        Collections.reverse(addresses);
        for (var address:addresses) reversed.put(address,sources.get(address));
        assertEquals(routed,HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
            atlas,reversed,loaded(atlas),86400));
    }

    @Test void atlasOwnedRunoffWaitsForUnloadedDownstreamAndContinuesNextSeason() {
        var atlas=atlas();
        var firstRunoff=HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
            atlas,Map.of(B,3.0),Set.of(B),86400);
        assertTrue(firstRunoff.drainage().terminalOutflow().isEmpty());
        assertEquals(3.0,firstRunoff.drainage().pendingOutflow().values()
            .stream().mapToDouble(Double::doubleValue).sum(),1e-12);
        var first=HydrologySeasonalTopologyEpochV2.prepareUniform(
            bound(atlas),atlas,firstRunoff,Map.of(),2,
            edges(atlas),Set.of(B),Set.of(M));
        assertEquals(3.0,first.water().state().lakes().pendingM3().values()
            .stream().mapToDouble(Double::doubleValue).sum(),1e-12);
        var dry=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:atlas.routes()) dry.put(node.address(),0.0);
        var nextRunoff=HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
            atlas,dry,loaded(atlas),86400);
        var second=HydrologySeasonalTopologyEpochV2.prepareUniform(
            first.checkpoint(),atlas,nextRunoff,Map.of(),2,
            edges(atlas),loaded(atlas),Set.of(M));
        assertEquals(Map.of(M,3.0),second.water().state().dischargedM3());
        assertTrue(second.water().state().lakes().pendingM3().isEmpty());
    }

    @Test void atlasOwnedRunoffRejectsMissingForeignAndInvalidOwners() {
        var atlas=atlas();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
                atlas,Map.of(B,1.0),Set.of(B,M),86400));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
                atlas,Map.of(at(8,2),1.0),Set.of(at(8,2)),86400));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
                atlas,Map.of(B,Double.NaN),Set.of(B),86400));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
                atlas,Map.of(B,-1.0),Set.of(B),86400));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.routeOwnedRunoff(
                atlas,Map.of(B,1.0),Set.of(B),0));
    }

    @Test void bindingRejectsPreviouslyConsumedEmptyReceiptEpoch() {
        var inbox=new HydrologyEpochInboxV2();
        inbox.claim(0);
        var old=HydrologyCheckpointV2.capture(0,fresh().worldState(),inbox);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.bindFresh(old,atlas()));
    }

    @Test void boundLakeStoresWaterAndRejectsSpillContradictingAtlas() {
        var atlas=atlas();
        var old=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(B,0.0),Map.of())),
            new HydrologyEpochInboxV2());
        var first=HydrologySeasonalTopologyEpochV2.bindFresh(old,atlas);
        var nodes=List.of(new HydrologyFlowGraphV2.Node(B,2,null,false));
        var flow=HydrologyFlowGraphV2.solve(nodes);
        var runoff=new HydrologySeasonalGlobalRoutingV2.Result(
            flow,HydrologyConservationV2.verify(nodes,flow),
            Map.of(),Map.of(),Map.of(),Map.of(B,2.0/86400.0),86400);
        var basin=new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2);
        var authentic=atlas.routeAt(B).downstream();
        assertNotNull(authentic);
        var lake=new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            basin,0,0,authentic,1);
        var good=HydrologySeasonalTopologyEpochV2.prepareUniform(
            first,atlas,runoff,Map.of(B,lake),2,
            edges(atlas),Set.of(B),Set.of(M));
        assertEquals(2.0,good.water().state().lakes().lakeStorageM3().get(B),1e-12);
        assertTrue(good.water().state().lakes().pendingM3().isEmpty());
        var fake=authentic.equals(at(2,2))?at(4,2):at(2,2);
        var altered=new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            basin,0,0,fake,1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalTopologyEpochV2.prepareUniform(
                first,atlas,runoff,Map.of(B,altered),2,
                edges(atlas),Set.of(B),Set.of(M)));
    }

    @Test void oneCallOwnedEpochUsesAtlasToReachOcean() {
        var atlas=atlas();
        var owned=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:atlas.routes()) owned.put(node.address(),0.0);
        owned.put(B,3.0);
        owned.put(M,2.0);
        var prepared=HydrologySeasonalTopologyEpochV2.prepareOwnedUniform(
            bound(atlas),atlas,owned,Map.of(),loaded(atlas),2,86400);
        assertEquals(Map.of(M,5.0),prepared.water().state().dischargedM3());
        assertTrue(prepared.water().state().lakes().pendingM3().isEmpty());
    }

    @Test void atlasOwnedLakeCaptureStoresWaterAndSchedulesAgree() {
        var atlas=atlas();
        var old=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(B,0.0),Map.of())),
            new HydrologyEpochInboxV2());
        var first=HydrologySeasonalTopologyEpochV2.bindFresh(old,atlas);
        var lake=new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2),
            0,0,atlas.routeAt(B).downstream(),1);
        var uniform=HydrologySeasonalTopologyEpochV2.prepareOwnedUniform(
            first,atlas,Map.of(B,2.0),Map.of(B,lake),Set.of(B),2,86400);
        assertEquals(2.0,uniform.water().state().lakes().lakeStorageM3().get(B),1e-12);
        assertTrue(uniform.water().state().lakes().pendingM3().isEmpty());
        var schedule=Map.of(B,List.of(
            new HydrologySeasonalLakeIntegratorV2.Interval(1,0),
            new HydrologySeasonalLakeIntegratorV2.Interval(1,0)));
        var scheduled=HydrologySeasonalTopologyEpochV2.prepareOwnedScheduled(
            first,atlas,Map.of(B,2.0),Map.of(B,lake),schedule,Set.of(B),86400);
        assertEquals(uniform.checkpoint(),scheduled.checkpoint());
    }

    @Test void atlasOwnedLakeOverflowSurvivesUnloadedRegionAndSettlesNextSeason() {
        var atlas=atlas();
        var old=HydrologyCheckpointV2.capture(0,
            HydrologySeasonalLakeCarryoverStateV2.encode(
                new HydrologySeasonalLakeCarryoverStateV2.State(
                    "world",0,Map.of(B,0.0),Map.of())),
            new HydrologyEpochInboxV2());
        var first=HydrologySeasonalTopologyEpochV2.bindFresh(old,atlas);
        var basin=new LakeReservoirV2.Basin(new double[]{0},new double[]{1},2);
        var lake=new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            basin,0,0,atlas.routeAt(B).downstream(),1);
        var season1=HydrologySeasonalTopologyEpochV2.prepareOwnedUniform(
            first,atlas,Map.of(B,3.0),Map.of(B,lake),Set.of(B),2,86400);
        assertEquals(2.0,season1.water().state().lakes().lakeStorageM3().get(B),1e-12);
        assertEquals(1.0,season1.water().state().lakes().pendingM3().values()
            .stream().mapToDouble(Double::doubleValue).sum(),1e-12);
        var dry=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:atlas.routes()) dry.put(node.address(),0.0);
        var nextLake=new HydrologySeasonalLaggedLakeNetworkV2.Lake(
            basin,2,0,atlas.routeAt(B).downstream(),1);
        var season2=HydrologySeasonalTopologyEpochV2.prepareOwnedUniform(
            season1.checkpoint(),atlas,dry,Map.of(B,nextLake),loaded(atlas),2,86400);
        assertEquals(Map.of(M,1.0),season2.water().state().dischargedM3());
        assertEquals(2.0,season2.water().state().lakes().lakeStorageM3().get(B),1e-12);
        assertTrue(season2.water().state().lakes().pendingM3().isEmpty());
    }

}
