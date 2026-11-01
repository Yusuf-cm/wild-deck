package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Durable closed-sphere lake routing with exactly-once boundary receipts. */
class HydrologyClosedLakeEpochV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static HydrologySphericalLakeBasinsV2.Plan planet(
            GlobalHydrologyAddressV2.Sample lake,
            GlobalHydrologyAddressV2.Sample outlet,
            double lakeRain,double outletRain) {
        var nodes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(long y=0;y<=4;y++) {
            long count=(y==0||y==4)?1:8;
            for(long x=0;x<count;x++) {
                var address=at(x,y);
                if(address.equals(lake))
                    nodes.add(new HydrologyFlowGraphV2.Node(
                        address,lakeRain,outlet,false));
                else
                    nodes.add(new HydrologyFlowGraphV2.Node(address,
                        address.equals(outlet)?outletRain:0,null,true));
            }
        }
        assertEquals(26,nodes.size());
        var reservoir=new LakeReservoirV2.Basin(
            new double[]{0},new double[]{1},5);
        var basin=new HydrologySphericalLakeBasinsV2.Lake(
            lake,List.of(lake),outlet,outlet,reservoir);
        return new HydrologySphericalLakeBasinsV2.Plan(nodes,List.of(basin));
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
            HydrologyLakeNetworkStepV2.Forcing> forcing(
            GlobalHydrologyAddressV2.Sample lake,double extra) {
        return Map.of(lake,new HydrologyLakeNetworkStepV2.Forcing(extra,0,4));
    }

    private static String initial() {
        return HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("initial",Map.of()));
    }

    @Test void confluenceReceiptsFillLakeThenSpillWithoutRestartReplay()
            throws IOException {
        var lake=at(1,2);
        var outlet=at(2,2);
        var path=directory.resolve("closed-lakes.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        var first=new HydrologyTransferLedgerV2.Key(1,at(1,1),lake);
        var second=new HydrologyTransferLedgerV2.Key(1,at(0,2),lake);
        assertTrue(runner.receive(first,4));
        assertTrue(runner.receive(second,3));
        assertFalse(runner.receive(first,4));
        var recovered=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var result=recovered.advanceClosedSphericalLakeNetwork("winter",
            planet(lake,outlet,2,3),forcing(lake,0));
        // Two independent receipts plus locally owned rainfall: 4+3+2.
        // Five units are stored, four spill, and three bypass the lake at
        // the independently owned outlet: total 12 units, no duplication.
        assertEquals(9,result.lakeInflow().get(lake),1e-10);
        assertEquals(4,result.lakeOverflow().get(lake),1e-10);
        assertEquals(7,result.terminalOutflow().get(outlet),1e-10);
        assertEquals(5,result.state().volumes().get(lake),1e-10);
        assertEquals(12,result.budget().sourceWater(),1e-10);
        assertEquals(0,result.budget().residual(),1e-10);
        assertEquals("winter",result.state().world());
        assertEquals(7,recovered.receiptBudget().deliveredVolume(),1e-10);
        assertEquals(0,recovered.receiptBudget().pendingVolume(),1e-10);

        var restarted=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(1,restarted.epoch());
        assertEquals(result.state(),
            HydrologyLakeStateV2.decode(restarted.worldState()));
        var spring=restarted.advanceClosedSphericalLakeNetwork("spring",
            planet(lake,outlet,0,1),forcing(lake,2));
        assertEquals(2,spring.lakeOverflow().get(lake),1e-10);
        assertEquals(3,spring.terminalOutflow().get(outlet),1e-10);
        assertEquals(5,spring.state().volumes().get(lake),1e-10);
        assertEquals(0,spring.budget().residual(),1e-10);
        assertEquals("spring",HydrologyLakeStateV2.decode(
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState()).world());
        assertEquals(7,restarted.receiptBudget().deliveredVolume(),1e-10);
    }

    @Test void invalidPartialSphereCannotConsumePendingLakeWater()
            throws IOException {
        var lake=at(1,2);
        var outlet=at(2,2);
        var path=directory.resolve("fail-closed.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        assertTrue(runner.receive(
            new HydrologyTransferLedgerV2.Key(1,at(1,1),lake),7));
        var full=planet(lake,outlet,0,0);
        var partial=new ArrayList<>(full.routes());
        partial.removeIf(node->node.address().equals(at(7,2)));
        assertThrows(IllegalArgumentException.class,()->
            runner.advanceClosedSphericalLakeNetwork("bad",
                new HydrologySphericalLakeBasinsV2.Plan(partial,full.lakes()),
                forcing(lake,0)));
        assertEquals(0,runner.epoch());
        assertEquals(7,runner.receiptBudget().pendingVolume(),1e-10);
        assertEquals("initial",HydrologyLakeStateV2.decode(
            HydrologyWorldRunnerV2.open(path,0,"ignored").worldState()).world());
        var good=runner.advanceClosedSphericalLakeNetwork("good",
            full,forcing(lake,0));
        assertEquals(5,good.state().volumes().get(lake),1e-10);
        assertEquals(2,good.terminalOutflow().get(outlet),1e-10);
        assertEquals(0,good.budget().residual(),1e-10);
    }

    @Test void polarAndDatelineReceiptsUseOneCanonicalLakeOwner()
            throws IOException {
        var pole=at(0,0);
        var outlet=at(0,1);
        var runner=HydrologyWorldRunnerV2.open(
            directory.resolve("pole.chk"),0,initial());
        var seam=at(7,1);
        var meridian=at(0,1);
        assertTrue(HydrologySphericalTopologyV2.adjacent(seam,pole));
        assertTrue(HydrologySphericalTopologyV2.adjacent(meridian,pole));
        runner.receive(new HydrologyTransferLedgerV2.Key(1,seam,pole),6);
        runner.receive(new HydrologyTransferLedgerV2.Key(1,meridian,pole),4);
        var result=runner.advanceClosedSphericalLakeNetwork("polar",
            planet(pole,outlet,0,2),forcing(pole,0));
        assertEquals(10,result.lakeInflow().get(pole),1e-10);
        assertEquals(5,result.state().volumes().get(pole),1e-10);
        assertEquals(7,result.terminalOutflow().get(outlet),1e-10);
        assertEquals(12,result.budget().sourceWater(),1e-10);
        assertEquals(0,result.budget().residual(),1e-10);
    }
}
