package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLongHorizonConservationV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }

    private static List<List<HydrologyFlowGraphV2.Node>> loaded(
            long x,double water,Long downstream,boolean terminal) {
        return List.of(List.of(new HydrologyFlowGraphV2.Node(
            at(x),water,downstream==null?null:at(downstream),terminal)));
    }

    @Test void waterCrossesThreeUnloadedBoundariesAcrossSixDiskRestarts()
            throws IOException {
        Path path=directory.resolve("world.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,"seed");
        var first=runner.advanceAndQueueKnownFrontier("step1",loaded(4,5,5L,false));
        assertEquals(1,first.queuedTransfers());
        assertEquals(5,first.committed().flow().pendingOutflow().get(at(5)),1e-9);
        assertEquals(1,runner.epoch());

        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(List.of(new HydrologyRegionSchedulerV2.Region(1,0)),
            runner.pendingWaterRegions());
        assertFalse(runner.receive(new HydrologyTransferLedgerV2.Key(2,at(4),at(5)),5));
        var second=runner.advanceAndQueueKnownFrontier("step2",loaded(12,0,null,true));
        assertEquals(0,second.queuedTransfers());
        assertEquals(1,runner.pendingWaterRegions().size());

        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var third=runner.advanceAndQueueKnownFrontier("step3",loaded(5,2,6L,false));
        assertEquals(1,third.queuedTransfers());
        assertEquals(7,third.committed().flow().pendingOutflow().get(at(6)),1e-9);

        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        runner.advanceAndQueueKnownFrontier("step4",loaded(12,0,null,true));
        assertEquals(1,runner.pendingWaterRegions().size());

        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var fifth=runner.advanceAndQueueKnownFrontier("step5",loaded(6,3,7L,false));
        assertEquals(10,fifth.committed().flow().pendingOutflow().get(at(7)),1e-9);

        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var sixth=runner.advanceAndQueueKnownFrontier("step6",loaded(7,1,null,true));
        assertEquals(11,sixth.committed().flow().terminalOutflow().get(at(7)),1e-9);
        assertEquals(0,runner.pendingWaterRegions().size());

        var recovered=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(6,recovered.epoch());
        assertEquals("step6",recovered.worldState());
        assertEquals(3,recovered.inboxSnapshot().receipts().size());
        assertEquals(3,recovered.inboxSnapshot().delivered().size());
        assertEquals(0,recovered.pendingWaterRegions().size());
        assertThrows(IllegalArgumentException.class,()->
            recovered.receive(new HydrologyTransferLedgerV2.Key(2,at(4),at(5)),5));
    }
}
