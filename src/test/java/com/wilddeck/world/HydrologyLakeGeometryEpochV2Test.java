package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeGeometryEpochV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(3,2);
    private static final GlobalHydrologyAddressV2.Sample B=at(4,2);
    private static final GlobalHydrologyAddressV2.Sample WEST=at(2,2);
    private static final GlobalHydrologyAddressV2.Sample EAST=at(5,2);

    private static HydrologySphericalLakeBasinsV2.Plan planet(boolean split,
                                                               boolean narrow) {
        var routes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(long y=0;y<=4;y++) {
            long count=y==0||y==4?1:8;
            for(long x=0;x<count;x++) {
                var address=at(x,y);
                GlobalHydrologyAddressV2.Sample next=null;
                if(address.equals(A))next=split?WEST:B;
                if(address.equals(B))next=EAST;
                routes.add(new HydrologyFlowGraphV2.Node(
                    address,0,next,next==null));
            }
        }
        assertEquals(26,routes.size());
        var lakes=new ArrayList<HydrologySphericalLakeBasinsV2.Lake>();
        if(split) {
            double area=narrow?0.5:1.0;
            lakes.add(new HydrologySphericalLakeBasinsV2.Lake(
                A,List.of(A),WEST,WEST,
                new LakeReservoirV2.Basin(new double[]{0},
                    new double[]{area},5)));
            lakes.add(new HydrologySphericalLakeBasinsV2.Lake(
                B,List.of(B),EAST,EAST,
                new LakeReservoirV2.Basin(new double[]{0},
                    new double[]{area},5)));
        } else {
            lakes.add(new HydrologySphericalLakeBasinsV2.Lake(
                A,List.of(A,B),EAST,EAST,
                new LakeReservoirV2.Basin(new double[]{0,0},
                    new double[]{1,1},10)));
        }
        return new HydrologySphericalLakeBasinsV2.Plan(routes,lakes);
    }

    @Test void wetSplitQueuesTwoConservedReceiptsAndSurvivesRestart()
            throws IOException {
        var path=directory.resolve("wet-split.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,
            HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State(
                "before",Map.of(A,18.0))));
        var transition=HydrologyLakeGeometryEpochV2.advance(
            runner,"after",planet(false,false),planet(true,false));
        assertEquals(1,runner.epoch());
        assertEquals(18,transition.geometry().initialStorage(),1e-9);
        assertEquals(10,transition.geometry().finalStorage(),1e-9);
        assertEquals(8,transition.geometry().released(),1e-9);
        assertEquals(2,transition.routed().queuedTransfers());
        assertEquals(8,runner.receiptBudget().pendingVolume(),1e-9);
        var recovered=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals("after",HydrologyLakeStateV2.decode(
            recovered.worldState()).world());
        assertEquals(5,HydrologyLakeStateV2.decode(
            recovered.worldState()).volumes().get(A),1e-9);
        assertEquals(5,HydrologyLakeStateV2.decode(
            recovered.worldState()).volumes().get(B),1e-9);
        var receiptKeys=recovered.inboxSnapshot().receipts().keySet();
        assertEquals(2,receiptKeys.size());
        assertTrue(receiptKeys.contains(
            new HydrologyTransferLedgerV2.Key(2,A,WEST)));
        assertTrue(receiptKeys.contains(
            new HydrologyTransferLedgerV2.Key(2,B,EAST)));
        var terminal=recovered.advance(recovered.worldState(),List.of(List.of(
            new HydrologyFlowGraphV2.Node(WEST,0,null,true),
            new HydrologyFlowGraphV2.Node(EAST,0,null,true))));
        assertEquals(4,terminal.terminalOutflow().get(WEST),1e-9);
        assertEquals(4,terminal.terminalOutflow().get(EAST),1e-9);
        assertEquals(0,recovered.receiptBudget().pendingVolume(),1e-9);
        assertEquals(8,recovered.receiptBudget().deliveredVolume(),1e-9);
        var again=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(2,again.epoch());
        assertEquals(0,again.receiptBudget().pendingVolume(),1e-9);
    }

    @Test void dryGeometryEpochAdvancesWithoutInventingTransfer() throws IOException {
        var path=directory.resolve("dry.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,
            HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State(
                "before",Map.of(A,8.0))));
        var advanced=HydrologyLakeGeometryEpochV2.advance(
            runner,"same",planet(false,false),planet(false,false));
        assertEquals(1,runner.epoch());
        assertEquals(0,advanced.routed().queuedTransfers());
        assertEquals(0,advanced.geometry().released(),0);
        assertEquals(8,advanced.geometry().finalStorage(),1e-9);
        assertEquals(0,runner.receiptBudget().pendingVolume(),0);
    }

    @Test void incomingLakeReceiptRejectsTransitionWithoutConsumingWater()
            throws IOException {
        var path=directory.resolve("pending.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,
            HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State(
                "before",Map.of(A,18.0))));
        var key=new HydrologyTransferLedgerV2.Key(1,WEST,A);
        runner.receive(key,3);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeGeometryEpochV2.advance(
                runner,"after",planet(false,false),planet(true,false)));
        assertEquals(0,runner.epoch());
        assertEquals(3,runner.receiptBudget().pendingVolume(),1e-9);
        assertEquals("before",HydrologyLakeStateV2.decode(
            runner.worldState()).world());
    }

    @Test void incompletePlanetFailsBeforeCheckpoint() throws IOException {
        var path=directory.resolve("incomplete.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,
            HydrologyLakeStateV2.encode(new HydrologyLakeStateV2.State(
                "before",Map.of(A,18.0))));
        var full=planet(false,false);
        var partial=new ArrayList<>(full.routes());
        partial.remove(0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeGeometryEpochV2.advance(runner,"bad",
                new HydrologySphericalLakeBasinsV2.Plan(partial,full.lakes()),
                planet(true,false)));
        assertEquals(0,runner.epoch());
        assertEquals(0,runner.receiptBudget().pendingVolume(),0);
    }
}
