package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalFrontierPersistenceV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,5);
    }
    private static List<List<HydrologyFlowGraphV2.Node>> loaded(
            long x,double water,Long downstream,boolean terminal) {
        return List.of(List.of(new HydrologyFlowGraphV2.Node(
            at(x),water,downstream==null?null:at(downstream),terminal)));
    }

    @Test void datelineWaterWaitsUnloadedEpochAndSurvivesTwoRestarts()
            throws IOException {
        Path checkpoint=directory.resolve("spherical-world.chk");
        var runner=HydrologyWorldRunnerV2.open(checkpoint,0,"seed");
        var first=runner.advanceAndQueueKnownFrontier(
            "source",loaded(31,3,0L,false));
        assertEquals(1,first.queuedTransfers());
        assertEquals(3,runner.receiptBudget().pendingVolume(),1e-12);
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(0,1)),
            runner.pendingWaterRegions());
        var seamReach=new HydrologyRiverIdentityV2.ReachId(at(31),at(0));
        assertEquals(seamReach,HydrologyRiverIdentityV2.knownReaches(
            loaded(31,3,0L,false).get(0)).iterator().next());

        runner=HydrologyWorldRunnerV2.open(checkpoint,0,"ignored");
        runner.advanceAndQueueKnownFrontier("still-unloaded",loaded(10,0,null,true));
        assertEquals(3,runner.receiptBudget().pendingVolume(),1e-12);
        assertEquals(0,runner.receiptBudget().deliveredVolume(),1e-12);

        runner=HydrologyWorldRunnerV2.open(checkpoint,0,"ignored");
        var third=runner.advanceAndQueueKnownFrontier(
            "seam-loaded",loaded(0,4,1L,false));
        assertEquals(7,third.committed().flow().pendingOutflow().get(at(1)),1e-12);
        assertEquals(7,runner.receiptBudget().pendingVolume(),1e-12);
        assertEquals(3,runner.receiptBudget().deliveredVolume(),1e-12);

        runner=HydrologyWorldRunnerV2.open(checkpoint,0,"ignored");
        var fourth=runner.advanceAndQueueKnownFrontier(
            "ocean",loaded(1,5,null,true));
        assertEquals(12,fourth.committed().flow().terminalOutflow().get(at(1)),1e-12);
        assertEquals(0,runner.receiptBudget().pendingVolume(),1e-12);
        assertEquals(10,runner.receiptBudget().deliveredVolume(),1e-12);
        assertTrue(runner.pendingWaterRegions().isEmpty());

        var recovered=HydrologyWorldRunnerV2.open(checkpoint,0,"ignored");
        assertEquals(4,recovered.epoch());
        assertEquals("ocean",recovered.worldState());
        assertThrows(IllegalArgumentException.class,()->
            recovered.receive(new HydrologyTransferLedgerV2.Key(2,at(31),at(0)),3));
        assertEquals(0,recovered.advanceAndQueueKnownFrontier(
            "no-replay",loaded(1,0,null,true))
            .committed().flow().terminalOutflow().get(at(1)),1e-12);
    }
}
