package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyPendingRegionRunnerV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,3);
    }
    private static HydrologyRegionSchedulerV2.Region region(long x) {
        return new HydrologyRegionSchedulerV2.Region(x,0);
    }

    @Test void parkedWaterSelectsDestinationAndFlowsAcrossNextUnloadedRegion()
            throws IOException {
        Path checkpoint=directory.resolve("world.chk");
        var runner=HydrologyWorldRunnerV2.open(checkpoint,0,"initial");
        var inbound=new HydrologyTransferLedgerV2.Key(1,at(7),at(8));
        assertTrue(runner.receive(inbound,5));
        assertFalse(runner.receive(inbound,5));
        assertEquals(List.of(region(1)),runner.pendingWaterRegions());

        var visited=new ArrayList<HydrologyRegionSchedulerV2.Region>();
        var first=runner.advancePendingRegionsAndQueue("epoch-1",List.of(),0,1,r->{
            visited.add(r);
            return List.of(new HydrologyFlowGraphV2.Node(at(8),1,at(9),false));
        });
        assertEquals(List.of(region(1)),visited);
        assertEquals(1,first.queuedTransfers());
        assertEquals(6,first.committed().flow().pendingOutflow().get(at(9)),1e-9);
        assertEquals(1,runner.epoch());

        var restored=HydrologyWorldRunnerV2.open(checkpoint,0,"ignored");
        assertEquals(List.of(region(2)),restored.pendingWaterRegions());
        visited.clear();
        var second=restored.advancePendingRegionsAndQueue("epoch-2",List.of(),0,1,r->{
            visited.add(r);
            return List.of(new HydrologyFlowGraphV2.Node(at(9),0,null,true));
        });
        assertEquals(List.of(region(2)),visited);
        assertEquals(0,second.queuedTransfers());
        assertEquals(6,second.committed().flow().terminalOutflow().get(at(9)),1e-9);
        assertEquals(2,restored.epoch());
        assertTrue(HydrologyWorldRunnerV2.open(checkpoint,0,"ignored")
            .pendingWaterRegions().isEmpty());
        assertThrows(IllegalArgumentException.class,()->restored.receive(inbound,5));
    }

    @Test void budgetFailureLeavesCheckpointAndInboxUntouched() throws IOException {
        var runner=HydrologyWorldRunnerV2.open(directory.resolve("budget.chk"),0,"initial");
        runner.receive(new HydrologyTransferLedgerV2.Key(1,at(7),at(8)),4);
        int[] calls={0};
        assertThrows(IllegalArgumentException.class,()->
            runner.advancePendingRegionsAndQueue("should-not-commit",List.of(),1,8,r->{
                calls[0]++;
                return List.of();
            }));
        assertEquals(0,calls[0]);
        assertEquals(0,runner.epoch());
        assertEquals(List.of(region(1)),runner.pendingWaterRegions());
    }

    @Test void pendingAndExplicitFocusDeduplicateBeforeLoading() throws IOException {
        var runner=HydrologyWorldRunnerV2.open(directory.resolve("dedupe.chk"),0,"initial");
        runner.receive(new HydrologyTransferLedgerV2.Key(1,at(7),at(8)),4);
        int[] calls={0};
        var result=runner.advancePendingRegionsAndQueue("committed",
            List.of(region(1),region(1)),0,1,r->{
                calls[0]++;
                return List.of(new HydrologyFlowGraphV2.Node(at(8),0,null,true));
            });
        assertEquals(1,calls[0]);
        assertEquals(4,result.committed().flow().terminalOutflow().get(at(8)),1e-9);
    }
}
