package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeFrontierEpochV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static HydrologyLakeEpochV2.FrontierPeriod lake(
            GlobalHydrologyAddressV2.Sample anchor,
            GlobalHydrologyAddressV2.Sample destination) {
        return new HydrologyLakeEpochV2.FrontierPeriod(
            anchor,anchor,destination,
            new LakeReservoirV2.Basin(new double[]{0},new double[]{10},2),
            10,new SeasonalLakeCycleV2.Period(3,0,0,1));
    }
    private static String initial() {
        return HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("initial",Map.of()));
    }

    @Test void spillToUnloadedRegionSurvivesRestartAndIsConsumedOnce()
            throws IOException {
        var source=at(4,3);
        var destination=at(5,3);
        var path=directory.resolve("lake-frontier.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        var exported=HydrologyLakeEpochV2.advanceWithFrontier(
            runner,"wet",List.of(
                new HydrologyFlowGraphV2.Node(source,0,destination,false)),
            List.of(lake(source,destination)),4);
        assertEquals(1,exported.routed().queuedTransfers());
        assertEquals(10,exported.routed().committed().flow()
            .pendingOutflow().get(destination),1e-9);
        assertEquals(20,HydrologyLakeStateV2.decode(runner.worldState())
            .volumes().get(source),1e-9);
        assertEquals(10,runner.receiptBudget().pendingVolume(),1e-9);

        var restarted=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,restarted.epoch());
        var received=restarted.advanceAndQueueKnownFrontier(
            restarted.worldState(),List.of(List.of(
                new HydrologyFlowGraphV2.Node(destination,2,null,true))));
        assertEquals(12,received.committed().flow()
            .terminalOutflow().get(destination),1e-9);
        assertEquals(0,restarted.receiptBudget().pendingVolume(),1e-9);
        assertEquals(10,restarted.receiptBudget().deliveredVolume(),1e-9);
        assertEquals(20,HydrologyLakeStateV2.decode(
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState())
            .volumes().get(source),1e-9);

        var again=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var dry=again.advanceAndQueueKnownFrontier(
            again.worldState(),List.of(List.of(
                new HydrologyFlowGraphV2.Node(destination,2,null,true))));
        assertEquals(2,dry.committed().flow()
            .terminalOutflow().get(destination),1e-9);
        assertEquals(10,again.receiptBudget().deliveredVolume(),1e-9);
    }

    @Test void twoLakeSpillsConvergeAsDistinctDurableReceipts()
            throws IOException {
        var west=at(4,3);
        var north=at(5,2);
        var destination=at(5,3);
        var path=directory.resolve("confluence.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        var export=HydrologyLakeEpochV2.advanceWithFrontier(
            runner,"lakes",List.of(
                new HydrologyFlowGraphV2.Node(west,0,destination,false),
                new HydrologyFlowGraphV2.Node(north,0,destination,false)),
            List.of(lake(west,destination),lake(north,destination)),2);
        assertEquals(2,export.routed().queuedTransfers());
        assertEquals(20,runner.receiptBudget().pendingVolume(),1e-9);
        assertEquals(2,runner.inboxSnapshot().receipts().size());
        var restored=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var delivered=restored.advanceAndQueueKnownFrontier(
            restored.worldState(),List.of(List.of(
                new HydrologyFlowGraphV2.Node(destination,1,null,true))));
        assertEquals(21,delivered.committed().flow()
            .terminalOutflow().get(destination),1e-9);
        assertEquals(20,restored.receiptBudget().deliveredVolume(),1e-9);
        assertEquals(40,HydrologyLakeStateV2.decode(restored.worldState())
            .volumes().values().stream().mapToDouble(Double::doubleValue).sum(),1e-9);
    }

    @Test void unverifiedOrSharedSpillEdgeFailsWithoutCheckpointMutation()
            throws IOException {
        var source=at(4,3);
        var destination=at(5,3);
        var runner=HydrologyWorldRunnerV2.open(
            directory.resolve("invalid.chk"),0,initial());
        var nodes=List.of(new HydrologyFlowGraphV2.Node(
            source,0,destination,false));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeEpochV2.advanceWithFrontier(runner,"bad",
                nodes,List.of(lake(source,at(6,3))),1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeEpochV2.advanceWithFrontier(runner,"bad",
                nodes,List.of(lake(source,destination),
                    lake(source,destination)),1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeEpochV2.advanceWithFrontier(runner,"bad",
                List.of(),List.of(lake(source,destination)),1));
        assertEquals(0,runner.epoch());
        assertEquals("initial",HydrologyLakeStateV2.decode(
            runner.worldState()).world());
        assertTrue(runner.inboxSnapshot().receipts().isEmpty());
    }
}
