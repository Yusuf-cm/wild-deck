package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalRegionSchedulerV2Test {
    @TempDir Path directory;
    private static HydrologyRegionSchedulerV2.Region region(long x,long y) {
        return new HydrologyRegionSchedulerV2.Region(x,y);
    }
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    @Test void datelineWrapsAndPoleClampsWithoutDuplicateTileLoads() {
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var loaded=HydrologySphericalRegionSchedulerV2.load(
            List.of(region(0,0)),1,1,6,r->{
                visited.add(r);
                return List.of();
            });
        assertEquals(6,loaded.size());
        assertEquals(List.of(region(0,0),region(1,0),region(3,0),
                             region(0,1),region(1,1),region(3,1)),visited);
        visited.clear();
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalRegionSchedulerV2.load(
                List.of(region(0,0)),1,1,5,r->{
                    visited.add(r);
                    return List.of();
                }));
        assertTrue(visited.isEmpty());
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalRegionSchedulerV2.load(
                List.of(region(-1,0)),1,0,1,r->List.of()));
    }

    @Test void parkedSeamWaterAddsOwningRegionAndDeduplicatesFocus() {
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,at(7,3),at(0,3)),3);
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        HydrologySphericalRegionSchedulerV2.loadWithPending(
            List.of(region(3,1),region(3,1)),inbox.snapshot(),
            1,0,2,r->{
                visited.add(r);
                return List.of();
            });
        assertEquals(List.of(region(0,1),region(3,1)),visited);
    }

    @Test void pendingSphericalReceiptsMustMatchRequestedLevelAndCanonicalOwnership() {
        var wrongLevel = new HydrologyEpochInboxV2();
        wrongLevel.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(0, 2, 1, 1),
            new GlobalHydrologyAddressV2.Sample(0, 2, 0, 1)), 3);
        var alias = new HydrologyEpochInboxV2();
        alias.receive(new HydrologyTransferLedgerV2.Key(1, at(7, 2), at(8, 2)), 5);
        var loaded = new ArrayList<HydrologyRegionSchedulerV2.Region>();
        HydrologyRegionSchedulerV2.Provider provider = r -> {
            loaded.add(r);
            return List.of();
        };
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySphericalRegionSchedulerV2.loadWithPending(
                List.of(), wrongLevel.snapshot(), 1, 0, 4, provider));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySphericalRegionSchedulerV2.loadWithPending(
                List.of(), alias.snapshot(), 1, 0, 4, provider));
        assertTrue(loaded.isEmpty(), "Invalid receipts must fail before loading any tile");
    }

    @Test void checkpointRunnerLoadsPendingDatelineDestinationAutomatically()
            throws IOException {
        Path checkpoint=directory.resolve("spherical-runner.chk");
        var runner=HydrologyWorldRunnerV2.open(checkpoint,0,"seed");
        var seam=new HydrologyTransferLedgerV2.Key(1,at(7,3),at(0,3));
        assertTrue(runner.receive(seam,4));
        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var first=runner.advanceSphericalPendingRegionsAndQueue(
            "loaded",List.of(),1,0,1,r->{
                visited.add(r);
                return List.of(new HydrologyFlowGraphV2.Node(at(0,3),1,null,true));
            });
        assertEquals(List.of(region(0,1)),visited);
        assertEquals(5,first.committed().flow().terminalOutflow().get(at(0,3)),1e-12);
        var restored=HydrologyWorldRunnerV2.open(checkpoint,0,"ignored");
        assertTrue(restored.pendingWaterRegions().isEmpty());
        assertEquals(Set.of(seam),restored.inboxSnapshot().delivered());
        assertEquals(1,restored.epoch());
    }

    @Test void realSeededProviderConservesWaterAcrossWrappedHalo() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(terrain);
        var provider=new HydrologySphericalSlopeRegionProviderV2(
            terrain,climate,1,2,2,Set.of());
        var loaded=HydrologySphericalRegionSchedulerV2.load(
            List.of(region(0,1)),1,1,6,provider);
        var joined=HydrologyMosaicJoinV2.merge(loaded);
        var addresses=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for(var node:joined)assertTrue(addresses.add(node.address()));
        HydrologyConservationV2.verify(joined,HydrologyFlowGraphV2.solve(joined));
        assertEquals(loaded,HydrologySphericalRegionSchedulerV2.load(
            List.of(region(0,1)),1,1,6,provider));
    }
}
