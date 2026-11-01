package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Strict original-checkpoint replay across delivery, lake storage and terrain. */
class HydrologySedimentLakeReplayVerifierV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(0,2),
        B=at(1,2),C=at(2,2),D=at(3,2);

    private static HydrologySphericalLakeBasinsV2.Plan basin() {
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for (long y=0;y<=4;y++)
            for (long x=0;x<(y==0||y==4?1:8);x++) {
                var point=at(x,y);
                if (point.equals(B))
                    nodes.add(new HydrologyFlowGraphV2.Node(point,0,C,false));
                else if (point.equals(C))
                    nodes.add(new HydrologyFlowGraphV2.Node(point,0,D,false));
                else
                    nodes.add(new HydrologyFlowGraphV2.Node(point,0,null,true));
            }
        return new HydrologySphericalLakeBasinsV2.Plan(nodes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(B,List.of(B),C,D,
                new LakeReservoirV2.Basin(new double[]{0},new double[]{1},5))));
    }

    private record Fixture(
            Map<String,Path> senders,
            Map<String,HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep> steps,
            Map<String,HydrologyCheckpointV2.Checkpoint> originals,
            Path receiver) {}

    private Fixture fixture() throws IOException {
        var baseline=basin();
        var sender=directory.resolve("sender.chk");
        var initialized=HydrologySedimentSenderOutboxV2.initialize(sender);
        var receipt=new HydrologySedimentReceiptInboxV2.Receipt(0,1,A,B);
        HydrologySedimentSenderOutboxV2.appendDurable(sender,initialized,
            new HydrologySedimentFrontierTransfersV2.Outgoing(
                Map.of(receipt,2000.0),2000.0));
        var receiver=directory.resolve("receiver.chk");
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("initial",Map.of()));
        var lake=HydrologyLakeSedimentSnapshotV2.encode(baseline,baseline,
            new HydrologyLakeStateV2.State(terrain,Map.of(B,3.0)));
        var original=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(lake),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(receiver,original);
        var capacities=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for (var node:baseline.routes()) capacities.put(node.address(),10000.0);
        var step=new HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep(
            new HydrologySedimentLoadedRegionsV2.Receiver(receiver,List.of(B)),
            baseline,"next",sample->0.0,
            Map.of(B,new HydrologyLakeNetworkStepV2.Forcing(0,0,1)),
            capacities,2000,SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        return new Fixture(Map.of("sender",sender),Map.of("lake",step),
            Map.of("lake",original),receiver);
    }

    private static HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep
            withForcing(HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep s,
                        double additionalInflow) {
        return new HydrologySedimentLoadedLakeNetworkEpochV2.RegionStep(
            s.receiver(),s.baseline(),s.nextWorld(),s.seed(),
            Map.of(B,new HydrologyLakeNetworkStepV2.Forcing(
                additionalInflow,0,1)),
            s.capacity(),s.dryBulkDensity(),s.mode());
    }

    @Test void exactPlanIsReadOnlyAndMatchesPersistedWaterTerrainAndReceipts()
            throws IOException {
        var f=fixture();
        var planned=HydrologySedimentLoadedLakeNetworkEpochV2.planVerified(
            f.senders(),f.steps(),f.originals(),1);
        assertEquals(Set.of("lake"),planned.pending().keySet());
        assertEquals(f.originals().get("lake"),
            HydrologyCheckpointStoreV2.load(f.receiver()));
        var result=HydrologySedimentLoadedLakeNetworkEpochV2.advanceVerified(
            f.senders(),f.steps(),f.originals(),1);
        assertEquals(Set.of("lake"),result.advanced().keySet());
        var actual=HydrologyCheckpointStoreV2.load(f.receiver());
        var verified=HydrologySedimentLakeReplayVerifierV2.verify(
            f.originals().get("lake"),actual,f.senders(),
            f.steps().get("lake"),1);
        assertTrue(verified.completed());
        assertEquals(verified.expectedNext(),actual);
        assertEquals(2000,result.after().deliveredMass(),0.0);
    }

    @Test void exactReplaySkipsCommittedEpochWithoutDuplicateDeposition()
            throws IOException {
        var f=fixture();
        HydrologySedimentLoadedLakeNetworkEpochV2.advanceVerified(
            f.senders(),f.steps(),f.originals(),1);
        var checkpoint=HydrologyCheckpointStoreV2.load(f.receiver());
        var again=HydrologySedimentLoadedLakeNetworkEpochV2.advanceVerified(
            f.senders(),f.steps(),f.originals(),1);
        assertTrue(again.advanced().isEmpty());
        assertEquals(Set.of("lake"),again.alreadyCompleted());
        assertEquals(checkpoint,HydrologyCheckpointStoreV2.load(f.receiver()));
    }

    @Test void changedWaterForcingCannotMasqueradeAsCompletedReplay()
            throws IOException {
        var f=fixture();
        HydrologySedimentLoadedLakeNetworkEpochV2.advanceVerified(
            f.senders(),f.steps(),f.originals(),1);
        var checkpoint=HydrologyCheckpointStoreV2.load(f.receiver());
        var changed=Map.of("lake",withForcing(f.steps().get("lake"),1.0));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentLoadedLakeNetworkEpochV2.advanceVerified(
                f.senders(),changed,f.originals(),1));
        assertEquals(checkpoint,HydrologyCheckpointStoreV2.load(f.receiver()));
    }

    @Test void recoveryAfterReceiptDeliveryButBeforeLakeEpoch()
            throws IOException {
        var f=fixture();
        HydrologySedimentLoadedNetworkV2.deliver(f.senders(),Map.of(
            "lake",f.steps().get("lake").receiver()));
        var parked=HydrologyCheckpointStoreV2.load(f.receiver());
        assertEquals(0,parked.epoch());
        var planned=HydrologySedimentLoadedLakeNetworkEpochV2.planVerified(
            f.senders(),f.steps(),f.originals(),1);
        assertEquals(Set.of("lake"),planned.pending().keySet());
        HydrologySedimentLoadedLakeNetworkEpochV2.advanceVerified(
            f.senders(),f.steps(),f.originals(),1);
        assertEquals(1,HydrologyCheckpointStoreV2.load(f.receiver()).epoch());
    }

    @Test void changedOriginalBaselineAndMissingBaselineFailClosed()
            throws IOException {
        var f=fixture();
        var original=f.originals().get("lake");
        var wrong=new HydrologyCheckpointV2.Checkpoint(0,
            original.worldState(),HydrologyInboxCodecV2.encode(
                new HydrologyEpochInboxV2().snapshot()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentLoadedLakeNetworkEpochV2.planVerified(
                f.senders(),f.steps(),Map.of(),1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentLoadedLakeNetworkEpochV2.planVerified(
                f.senders(),f.steps(),Map.of("lake",
                    new HydrologyCheckpointV2.Checkpoint(3,
                        original.worldState(),original.inboxState())),1));
        assertEquals(original,HydrologyCheckpointStoreV2.load(f.receiver()));
    }

    @Test void newUnprovenReceiptInReceiverIsRejectedBeforeEpochCommit()
            throws IOException {
        var f=fixture();
        var original=f.originals().get("lake");
        var forged=new HydrologySedimentReceiptInboxV2.Receipt(
            0,1,at(0,3),at(1,3));
        var checkpoint=HydrologySedimentCheckpointV2.receive(
            f.receiver(),original,forged,2);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySedimentLoadedLakeNetworkEpochV2.advanceVerified(
                f.senders(),f.steps(),f.originals(),1));
        assertEquals(checkpoint,HydrologyCheckpointStoreV2.load(f.receiver()));
    }
}
