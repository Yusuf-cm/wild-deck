package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySedimentLoadedLakeEpochV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A=at(0,2), B=at(1,2), C=at(2,2), D=at(3,2), E=at(1,3);

    private static HydrologySphericalLakeBasinsV2.Plan baseline() {
        var nodes=new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(A,0,B,false),
            new HydrologyFlowGraphV2.Node(B,0,C,false),
            new HydrologyFlowGraphV2.Node(C,0,D,false),
            new HydrologyFlowGraphV2.Node(D,0,null,true)));
        for(long y=0;y<=4;y++)
            for(long x=0;x<(y==0||y==4?1:8);x++) {
                var address=at(x,y);
                if(!Set.of(A,B,C,D).contains(address))
                    nodes.add(new HydrologyFlowGraphV2.Node(address,0,null,true));
            }
        return new HydrologySphericalLakeBasinsV2.Plan(nodes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,D,
                new LakeReservoirV2.Basin(new double[]{0},new double[]{1},5))));
    }

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(
            long epoch,GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to) {
        return new HydrologySedimentReceiptInboxV2.Receipt(epoch,epoch+1,from,to);
    }

    private Path sender(String name,
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> receipts)
            throws IOException {
        Path file=directory.resolve(name+".sender");
        var first=HydrologySedimentSenderOutboxV2.initialize(file);
        double total=receipts.values().stream().mapToDouble(Double::doubleValue).sum();
        HydrologySedimentSenderOutboxV2.appendDurable(file,first,
            new HydrologySedimentFrontierTransfersV2.Outgoing(receipts,total));
        return file;
    }

    private Path receiver(String name,double initial,
            Map<GlobalHydrologyAddressV2.Sample,Double> elevations)
            throws IOException {
        Path path=directory.resolve(name+".receiver");
        var base=baseline();
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("initial",elevations));
        var snapshot=HydrologyLakeSedimentSnapshotV2.encode(base,base,
            new HydrologyLakeStateV2.State(terrain,Map.of(B,initial)));
        HydrologyCheckpointStoreV2.save(path,HydrologyCheckpointV2.capture(
            0,HydrologySedimentCheckpointV2.initialWorld(snapshot),
            new HydrologyEpochInboxV2()));
        return path;
    }

    private static Map<String,HydrologySedimentLoadedRegionsV2.Receiver> loaded(Path file) {
        return Map.of("lake",new HydrologySedimentLoadedRegionsV2.Receiver(
            file,List.of(B)));
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> capacity() {
        var map=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var node:baseline().routes())map.put(node.address(),10000.0);
        return map;
    }

    private static HydrologySedimentLoadedLakeEpochV2.Committed advance(
            Map<String,Path> senders,
            Map<String,HydrologySedimentLoadedRegionsV2.Receiver> loaded,
            String world) throws IOException {
        return HydrologySedimentLoadedLakeEpochV2.advanceWithTerrain(
            senders,loaded,"lake",baseline(),world,address->0.0,
            Map.of(B,new HydrologyLakeNetworkStepV2.Forcing(0,0,1)),
            capacity(),2000,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
    }

    private static HydrologySedimentSenderOutboxV2.Snapshot outbox(Path file)
            throws IOException {
        return HydrologySedimentSenderOutboxV2.restore(
            HydrologyCheckpointStoreV2.load(file));
    }

    @Test void twoSendersAdvanceOneLakeAndKeepUnloadedSedimentPending()
            throws IOException {
        var west=receipt(0,A,B);
        var north=receipt(0,E,B);
        var unloaded=receipt(1,B,C);
        Path first=sender("west",Map.of(west,1000.0,unloaded,3000.0));
        Path second=sender("north",Map.of(north,1000.0));
        Path target=receiver("lake",3,Map.of(at(7,3),7.0));
        var senders=Map.of("west",first,"north",second);
        var committed=advance(senders,loaded(target),"season-one");
        assertEquals(2,committed.delivery().newlyReceived());
        assertEquals(2000,committed.lake().result().imported(),0.0);
        assertEquals(2000,committed.afterEpoch().deliveredMass(),0.0);
        assertEquals(3000,committed.afterEpoch().inTransitMass(),0.0);
        assertEquals(0,committed.afterEpoch().awaitingMass(),0.0);
        assertEquals(1,committed.lake().checkpoint().epoch());
        assertEquals(Set.of(west),outbox(first).acknowledged());
        assertEquals(Set.of(north),outbox(second).acknowledged());
        var envelope=HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(target).worldState());
        assertEquals(Set.of(west,north),envelope.sediment().delivered());
        var state=HydrologyLakeSedimentSnapshotV2.decode(baseline(),envelope.world());
        assertEquals(4.0,state.plan().lakes().get(0).reservoir().capacity(),1e-12);
        var terrain=HydrologyTerrainOverlayV2.decode(state.water().world());
        assertEquals("season-one",terrain.world());
        assertEquals(1.0,terrain.elevations().get(B),0.0);
        assertEquals(7.0,terrain.elevations().get(at(7,3)),0.0);
        var audit=HydrologySedimentCrossRegionAuditV2.reconcileAllDurable(
            senders,Map.of("lake",target));
        assertEquals(5000,audit.sentMass(),0.0);
        assertEquals(2000,audit.deliveredMass(),0.0);
        assertEquals(3000,audit.inTransitMass(),0.0);
    }

    @Test void crashAfterDurableDeliveryCanResumeWithoutDoubleCounting()
            throws IOException {
        var r=receipt(0,A,B);
        Path sender=sender("retry",Map.of(r,2000.0));
        Path target=receiver("retry",3,Map.of());
        var senders=Map.of("sender",sender);
        HydrologySedimentLoadedNetworkV2.deliver(senders,loaded(target));
        var resumed=advance(senders,loaded(target),"season-one");
        assertEquals(0,resumed.delivery().newlyReceived());
        assertEquals(2000,resumed.lake().result().imported(),0.0);
        var next=advance(senders,loaded(target),"season-two");
        assertEquals(0,next.lake().result().imported(),0.0);
        assertEquals(0,next.delivery().newlyReceived());
        assertEquals(2,next.lake().checkpoint().epoch());
        assertEquals(2000,next.afterEpoch().deliveredMass(),0.0);
        assertEquals(0,next.afterEpoch().awaitingMass(),0.0);
        var persisted=HydrologySedimentCheckpointV2.decode(
            HydrologyCheckpointStoreV2.load(target).worldState());
        assertEquals(1,persisted.sediment().delivered().size());
        var lake=HydrologyLakeSedimentSnapshotV2.decode(baseline(),persisted.world());
        assertEquals(4.0,lake.plan().lakes().get(0).reservoir().capacity(),1e-12);
    }

    @Test void fullySiltedLakeFailsBeforeReceiverOrSenderWrites()
            throws IOException {
        Path sender=sender("silt",Map.of(receipt(0,A,B),10000.0));
        Path target=receiver("silt",3,Map.of());
        var senderBefore=HydrologyCheckpointStoreV2.load(sender);
        var receiverBefore=HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalArgumentException.class,()->
            advance(Map.of("sender",sender),loaded(target),"never"));
        assertEquals(senderBefore,HydrologyCheckpointStoreV2.load(sender));
        assertEquals(receiverBefore,HydrologyCheckpointStoreV2.load(target));
    }

    @Test void mismatchedLakeTerrainFailsBeforeAcknowledgment() throws IOException {
        Path sender=sender("mismatch",Map.of(receipt(0,A,B),2000.0));
        Path target=receiver("mismatch",3,Map.of(B,2.0));
        var senderBefore=HydrologyCheckpointStoreV2.load(sender);
        var receiverBefore=HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalArgumentException.class,()->
            advance(Map.of("sender",sender),loaded(target),"never"));
        assertEquals(senderBefore,HydrologyCheckpointStoreV2.load(sender));
        assertEquals(receiverBefore,HydrologyCheckpointStoreV2.load(target));
    }

    @Test void overlappingCrossResolutionLoadedOwnershipRejectsBeforeWrites()
            throws IOException {
        Path sender=sender("ownership",Map.of(receipt(0,A,B),2000.0));
        Path target=receiver("ownership",3,Map.of());
        Path other=receiver("other",3,Map.of());
        var alias=new GlobalHydrologyAddressV2.Sample(2,4,4,8);
        var loaded=new HashMap<>(loaded(target));
        loaded.put("other",new HydrologySedimentLoadedRegionsV2.Receiver(
            other,List.of(alias)));
        var before=HydrologyCheckpointStoreV2.load(target);
        assertThrows(IllegalArgumentException.class,()->
            advance(Map.of("sender",sender),loaded,"never"));
        assertEquals(before,HydrologyCheckpointStoreV2.load(target));
        assertTrue(outbox(sender).acknowledged().isEmpty());
    }

    @Test void pendingWaterBlocksLakeEpochWithoutSedimentDelivery()
            throws IOException {
        Path sender=sender("water",Map.of(receipt(0,A,B),2000.0));
        Path target=directory.resolve("water.receiver");
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,A,B),2);
        var base=baseline();
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("initial",Map.of()));
        var lake=HydrologyLakeSedimentSnapshotV2.encode(base,base,
            new HydrologyLakeStateV2.State(terrain,Map.of(B,3.0)));
        var modified=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(lake),inbox);
        HydrologyCheckpointStoreV2.save(target,modified);
        var senderBefore=HydrologyCheckpointStoreV2.load(sender);
        assertThrows(IllegalArgumentException.class,()->
            advance(Map.of("sender",sender),loaded(target),"never"));
        assertEquals(senderBefore,HydrologyCheckpointStoreV2.load(sender));
        assertEquals(modified,HydrologyCheckpointStoreV2.load(target));
    }
}
