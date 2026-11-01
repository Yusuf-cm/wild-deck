package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentLoadedLakeNetworkEpochV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A=at(0,2), B=at(1,2), C=at(2,2), D=at(3,2),
        E=at(1,3), F=at(2,3), G=at(3,3);

    private static HydrologySphericalLakeBasinsV2.Plan baseline(
            GlobalHydrologyAddressV2.Sample anchor,
            GlobalHydrologyAddressV2.Sample spill,
            GlobalHydrologyAddressV2.Sample outlet) {
        var nodes = new ArrayList<HydrologyFlowGraphV2.Node>();
        for (long y=0;y<=4;y++)
            for (long x=0;x<(y==0||y==4?1:8);x++) {
                var point=at(x,y);
                if (point.equals(anchor))
                    nodes.add(new HydrologyFlowGraphV2.Node(point,0,spill,false));
                else if (point.equals(spill))
                    nodes.add(new HydrologyFlowGraphV2.Node(point,0,outlet,false));
                else
                    nodes.add(new HydrologyFlowGraphV2.Node(point,0,null,true));
            }
        return new HydrologySphericalLakeBasinsV2.Plan(nodes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(anchor,List.of(anchor),
                spill,outlet,new LakeReservoirV2.Basin(
                    new double[]{0},new double[]{1},5))));
    }

    private Path sender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> receipts)
            throws IOException {
        Path path=directory.resolve(name+".sender");
        var first=HydrologySedimentSenderOutboxV2.initialize(path);
        double total=receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(path,first,
            new HydrologySedimentFrontierTransfersV2.Outgoing(receipts,total));
        return path;
    }

    private Path receiver(String name,
            HydrologySphericalLakeBasinsV2.Plan baseline,
            GlobalHydrologyAddressV2.Sample anchor,double existingBed)
            throws IOException {
        Path path=directory.resolve(name+".receiver");
        var overlay=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("initial",
                existingBed==0 ? Map.of() : Map.of(anchor,existingBed)));
        var lake=HydrologyLakeSedimentSnapshotV2.encode(baseline,baseline,
            new HydrologyLakeStateV2.State(overlay,Map.of(anchor,3.0)));
        HydrologyCheckpointStoreV2.save(path,HydrologyCheckpointV2.capture(
            0,HydrologySedimentCheckpointV2.initialWorld(lake),
            new HydrologyEpochInboxV2()));
        return path;
    }

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(
            long epoch,GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(epoch,epoch+1,from,to);
    }

    private static HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep step(
            Path path,GlobalHydrologyAddressV2.Sample anchor,
            HydrologySphericalLakeBasinsV2.Plan baseline) {
        var capacity=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:baseline.routes()) capacity.put(node.address(),10000.0);
        return new HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep(
            new HydrologySedimentLoadedRegionsV2.Receiver(path,List.of(anchor)),
            baseline,"season-one",address->0.0,
            Map.of(anchor,new HydrologyLakeNetworkStepV2.Forcing(0,0,1)),
            capacity,2000,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
    }

    private Map<String,HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep>
            regions(double eastBed) throws IOException {
        var west=baseline(B,C,D);
        var east=baseline(E,F,G);
        return Map.of(
            "west",step(receiver("west",west,B,0),B,west),
            "east",step(receiver("east",east,E,eastBed),E,east));
    }

    private static Map<String,Path> paths(
            Map<String,HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep> regions) {
        var result=new TreeMap<String,Path>();
        for (var entry:regions.entrySet())
            result.put(entry.getKey(),entry.getValue().receiver().checkpoint());
        return result;
    }

    @Test void twoLoadedLakesAndUnloadedDestinationConserveAllSediment()
            throws IOException {
        var west=receipt(0,A,B);
        var east=receipt(0,D,E);
        var unloaded=receipt(1,B,C);
        var senders=Map.of(
            "s1",sender("s1",Map.of(west,2000.0,unloaded,3000.0)),
            "s2",sender("s2",Map.of(east,1000.0)));
        var regions=regions(0);
        var planned=HydrologySedimentLoadedLakeNetworkEpochV2.plan(
            senders,regions,1);
        assertEquals(Set.of("west","east"),planned.pending().keySet());
        assertEquals(2,planned.delivery().pendingReceipts());
        assertEquals(3000,planned.delivery().pendingMass(),0.0);
        assertEquals(0,planned.completed().size());
        var result=HydrologySedimentLoadedLakeNetworkEpochV2.advance(
            senders,regions,1);
        assertEquals(Set.of("west","east"),result.advanced().keySet());
        assertEquals(3000,result.after().deliveredMass(),0.0);
        assertEquals(3000,result.after().inTransitMass(),0.0);
        assertEquals(0,result.after().awaitingMass(),0.0);
        for (var path:paths(regions).values())
            assertEquals(1,HydrologyCheckpointStoreV2.load(path).epoch());
        var first=HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(senders.get("s1")));
        assertEquals(Set.of(west),first.acknowledged());
    }

    @Test void invalidSecondLakeFailsPreflightWithoutCommittingFirst()
            throws IOException {
        var senders=Map.of("s1",sender("bad",Map.of(
            receipt(0,A,B),1000.0,receipt(0,D,E),1000.0)));
        var regions=regions(2);
        var beforeWest=HydrologyCheckpointStoreV2.load(
            regions.get("west").receiver().checkpoint());
        var beforeEast=HydrologyCheckpointStoreV2.load(
            regions.get("east").receiver().checkpoint());
        var beforeSender=HydrologyCheckpointStoreV2.load(senders.get("s1"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentLoadedLakeNetworkEpochV2.advance(senders,regions,1));
        assertEquals(beforeWest,HydrologyCheckpointStoreV2.load(
            regions.get("west").receiver().checkpoint()));
        assertEquals(beforeEast,HydrologyCheckpointStoreV2.load(
            regions.get("east").receiver().checkpoint()));
        assertEquals(beforeSender,HydrologyCheckpointStoreV2.load(senders.get("s1")));
    }

    @Test void partialEpochRetrySkipsAlreadyCommittedRegion()
            throws IOException {
        var senders=Map.of("s1",sender("retry",Map.of(
            receipt(0,A,B),2000.0,receipt(0,D,E),1000.0)));
        var regions=regions(0);
        var loaded=new TreeMap<String,HydrologySedimentLoadedRegionsV2.Receiver>();
        for (var entry:regions.entrySet())
            loaded.put(entry.getKey(),entry.getValue().receiver());
        var first=regions.get("east");
        HydrologySedimentLoadedLakeEpochV2.advanceWithTerrain(
            senders,loaded,"east",first.baseline(),first.nextWorld(),first.seed(),
            first.water(),first.capacity(),first.dryBulkDensity(),first.mode());
        var persisted=HydrologyCheckpointStoreV2.load(first.receiver().checkpoint());
        var planned=HydrologySedimentLoadedLakeNetworkEpochV2.plan(
            senders,regions,1);
        assertEquals(Set.of("east"),planned.completed());
        assertEquals(Set.of("west"),planned.pending().keySet());
        var resumed=HydrologySedimentLoadedLakeNetworkEpochV2.advance(
            senders,regions,1);
        assertEquals(Set.of("east"),resumed.alreadyCompleted());
        assertEquals(Set.of("west"),resumed.advanced().keySet());
        assertEquals(persisted,HydrologyCheckpointStoreV2.load(
            first.receiver().checkpoint()));
        assertEquals(3000,resumed.after().deliveredMass(),0.0);
    }

    @Test void replaySameTargetEpochDoesNotReapplySedimentOrWater()
            throws IOException {
        var senders=Map.of("s1",sender("replay",Map.of(
            receipt(0,A,B),2000.0,receipt(0,D,E),1000.0)));
        var regions=regions(0);
        HydrologySedimentLoadedLakeNetworkEpochV2.advance(senders,regions,1);
        var before=new TreeMap<String,HydrologyCheckpointV2.Checkpoint>();
        for (var entry:paths(regions).entrySet())
            before.put(entry.getKey(),HydrologyCheckpointStoreV2.load(entry.getValue()));
        var senderBefore=HydrologyCheckpointStoreV2.load(senders.get("s1"));
        var replay=HydrologySedimentLoadedLakeNetworkEpochV2.advance(
            senders,regions,1);
        assertTrue(replay.advanced().isEmpty());
        assertEquals(Set.of("west","east"),replay.alreadyCompleted());
        for (var entry:paths(regions).entrySet())
            assertEquals(before.get(entry.getKey()),
                HydrologyCheckpointStoreV2.load(entry.getValue()));
        assertEquals(senderBefore,HydrologyCheckpointStoreV2.load(senders.get("s1")));
    }

    @Test void mismatchedTargetEpochRejectsWithoutWriting() throws IOException {
        var senders=Map.of("s1",sender("epoch",Map.of(receipt(0,A,B),1000.0)));
        var regions=regions(0);
        var before=HydrologyCheckpointStoreV2.load(
            regions.get("east").receiver().checkpoint());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentLoadedLakeNetworkEpochV2.advance(senders,regions,3));
        assertEquals(before,HydrologyCheckpointStoreV2.load(
            regions.get("east").receiver().checkpoint()));
    }
    @Test void lakeOwnedByAnotherRegionIsRejectedBeforeAnyCheckpointWrites()
            throws IOException {
        var senders=Map.of("s1",sender("ownership",Map.of(
            receipt(0,A,B),1000.0)));
        var regions=new HashMap<>(regions(0));
        var east=regions.get("east");
        // The east receiver owns E, but this altered baseline evolves B.
        // A shared full-planet route graph must not imply shared lake beds.
        regions.put("east",step(east.receiver().checkpoint(),E,baseline(B,C,D)));
        var before=HydrologyCheckpointStoreV2.load(
            regions.get("west").receiver().checkpoint());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentLoadedLakeNetworkEpochV2.advance(senders,regions,1));
        assertEquals(before,HydrologyCheckpointStoreV2.load(
            regions.get("west").receiver().checkpoint()));
        assertTrue(HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(senders.get("s1")))
            .acknowledged().isEmpty());
    }

}
