package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end durable batch preflight, ownership and exact-checkpoint recovery. */
class HydrologyRiverbedLoadedNetworkEpochV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,2,x,y);
    }

    private static HydrologySphericalLakeBasinsV2.Plan drainage(long row) {
        var a=at(3,row);
        var b=at(4,row);
        var c=at(5,row);
        return new HydrologySphericalLakeBasinsV2.Plan(List.of(
            new HydrologyFlowGraphV2.Node(a,0,b,false),
            new HydrologyFlowGraphV2.Node(b,0,c,false),
            new HydrologyFlowGraphV2.Node(c,0,null,true)),List.of());
    }

    private static HydrologyGlobalSlopeRouterV2.Elevations seed(long row) {
        return sample -> sample.equals(at(3,row))?30:
            sample.equals(at(4,row))?20:sample.equals(at(5,row))?10:100;
    }

    private static Map<GlobalHydrologyAddressV2.Sample,Double> eroded(long row) {
        return Map.of(at(3,row),1000.0,at(4,row),0.0,at(5,row),0.0);
    }

    private static HydrologyRiverSedimentTransportV2.Result transport(long row) {
        return HydrologyRiverSedimentTransportV2.route(
            drainage(row),eroded(row),eroded(row));
    }

    private static HydrologyCheckpointV2.Checkpoint initial(Path path,long row)
            throws IOException {
        var terrain=HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State("before",Map.of()));
        var lake=HydrologyLakeSedimentSnapshotV2.encode(
            drainage(row),drainage(row),
            new HydrologyLakeStateV2.State(terrain,Map.of()));
        var checkpoint=HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(lake),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        return checkpoint;
    }

    private static HydrologyRiverbedLoadedNetworkEpochV2.RegionStep step(
            Path path,long row,HydrologyCheckpointV2.Checkpoint previous,
            String world,double maximumRise,
            List<GlobalHydrologyAddressV2.Sample> ownership) {
        return new HydrologyRiverbedLoadedNetworkEpochV2.RegionStep(
            new HydrologySedimentLoadedRegionsV2.Receiver(path,ownership),
            previous,drainage(row),world,seed(row),eroded(row),transport(row),
            Map.of(at(3,row),1.0,at(4,row),1.0),
            1000,2,maximumRise);
    }

    private static List<GlobalHydrologyAddressV2.Sample> owned(long row) {
        return List.of(at(3,row),at(4,row),at(5,row));
    }

    private Map<String,HydrologyRiverbedLoadedNetworkEpochV2.RegionStep> steps()
            throws IOException {
        var a=directory.resolve("a.chk");
        var b=directory.resolve("b.chk");
        return Map.of(
            "z",step(b,5,initial(b,5),"next-b",2,owned(5)),
            "a",step(a,3,initial(a,3),"next-a",2,owned(3)));
    }

    private static HydrologyRiverbedCheckpointV2.Planned commitOne(
            HydrologyRiverbedLoadedNetworkEpochV2.RegionStep s)
            throws IOException {
        return HydrologyRiverbedCheckpointV2.commitRouteVerified(
            s.receiver().checkpoint(),s.expectedPrevious(),s.baseline(),
            s.nextWorld(),s.seed(),s.eroded(),s.transport(),s.cellArea(),
            s.dryBulkDensity(),s.maximumIncision(),s.maximumRise());
    }

    @Test void plansAllRegionsWithoutWritesThenPersistsConservedBeds()
            throws IOException {
        var steps=steps();
        var a=steps.get("a").expectedPrevious();
        var b=steps.get("z").expectedPrevious();
        var plan=HydrologyRiverbedLoadedNetworkEpochV2.plan(Map.of(),steps,1);
        assertEquals(List.of("a","z"),new ArrayList<>(plan.pending().keySet()));
        assertTrue(plan.completed().isEmpty());
        assertEquals(2000.0,plan.erodedMass(),0.0);
        assertEquals(2000.0,plan.depositedMass(),0.0);
        assertEquals(a,HydrologyCheckpointStoreV2.load(steps.get("a").receiver().checkpoint()));
        assertEquals(b,HydrologyCheckpointStoreV2.load(steps.get("z").receiver().checkpoint()));

        var committed=HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1);
        assertEquals(List.of("a","z"),new ArrayList<>(committed.advanced().keySet()));
        assertTrue(committed.after().quiescent());
        for (var entry:steps.entrySet()) {
            var stored=HydrologyCheckpointStoreV2.load(entry.getValue().receiver().checkpoint());
            assertEquals(1,stored.epoch());
            assertEquals(plan.pending().get(entry.getKey()).next(),stored);
            var envelope=HydrologySedimentCheckpointV2.decode(stored.worldState());
            var lakes=HydrologyLakeSedimentSnapshotV2.decode(
                entry.getValue().baseline(),envelope.world());
            var terrain=HydrologyTerrainOverlayV2.decode(lakes.water().world());
            long row=entry.getKey().equals("a")?3:5;
            assertEquals(29.0,terrain.elevations().get(at(3,row)),0.0);
            assertEquals(21.0,terrain.elevations().get(at(4,row)),0.0);
        }
    }

    @Test void exactReplayAfterCompleteBatchIsIdempotent() throws IOException {
        var steps=steps();
        HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1);
        var result=HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1);
        assertTrue(result.advanced().isEmpty());
        assertEquals(Set.of("a","z"),result.alreadyCompleted());
    }

    @Test void resumesAfterFirstRegionWasDurablyCommitted() throws IOException {
        var steps=steps();
        var first=commitOne(steps.get("a"));
        var result=HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1);
        assertEquals(Set.of("a"),result.alreadyCompleted());
        assertEquals(Set.of("z"),result.advanced().keySet());
        assertEquals(first.next(),HydrologyCheckpointStoreV2.load(
            steps.get("a").receiver().checkpoint()));
        assertEquals(1,HydrologyCheckpointStoreV2.load(
            steps.get("z").receiver().checkpoint()).epoch());
    }

    @Test void invalidLaterRegionFailsPreflightWithoutPartialCommit()
            throws IOException {
        var steps=new HashMap<>(steps());
        var second=steps.get("z");
        steps.put("z",step(second.receiver().checkpoint(),5,
            second.expectedPrevious(),second.nextWorld(),0.1,owned(5)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1));
        for (var entry:steps.entrySet())
            assertEquals(entry.getValue().expectedPrevious(),
                HydrologyCheckpointStoreV2.load(entry.getValue().receiver().checkpoint()));
    }

    @Test void overlappingPhysicalOwnershipAndUnownedErosionFailClosed()
            throws IOException {
        var steps=new HashMap<>(steps());
        var second=steps.get("z");
        var overlapping=new ArrayList<>(owned(5));
        overlapping.add(at(3,3));
        steps.put("z",step(second.receiver().checkpoint(),5,
            second.expectedPrevious(),second.nextWorld(),2,overlapping));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedLoadedNetworkEpochV2.plan(Map.of(),steps,1));
        steps.put("z",step(second.receiver().checkpoint(),5,
            second.expectedPrevious(),second.nextWorld(),2,
            List.of(at(4,5),at(5,5))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1));
        assertEquals(0,HydrologyCheckpointStoreV2.load(
            steps.get("a").receiver().checkpoint()).epoch());
    }

    @Test void changedCompletedReplayParametersCannotBeAccepted()
            throws IOException {
        var steps=new HashMap<>(steps());
        commitOne(steps.get("a"));
        var original=steps.get("a");
        steps.put("a",step(original.receiver().checkpoint(),3,
            original.expectedPrevious(),"tampered-world",2,owned(3)));
        assertThrows(IOException.class,()->
            HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1));
        assertEquals(0,HydrologyCheckpointStoreV2.load(
            steps.get("z").receiver().checkpoint()).epoch());
    }

    @Test void parkedWaterRejectsWholeBatchWithoutSkippingReceipts()
            throws IOException {
        var steps=steps();
        var runner=HydrologyWorldRunnerV2.open(
            steps.get("z").receiver().checkpoint(),0,"ignored");
        assertTrue(runner.receive(new HydrologyTransferLedgerV2.Key(
            1,at(3,5),at(4,5)),3));
        var water=HydrologyCheckpointStoreV2.load(
            steps.get("z").receiver().checkpoint());
        assertThrows(IllegalStateException.class,()->
            HydrologyRiverbedLoadedNetworkEpochV2.advance(Map.of(),steps,1));
        assertEquals(water,HydrologyCheckpointStoreV2.load(
            steps.get("z").receiver().checkpoint()));
        assertEquals(0,HydrologyCheckpointStoreV2.load(
            steps.get("a").receiver().checkpoint()).epoch());
    }
}
