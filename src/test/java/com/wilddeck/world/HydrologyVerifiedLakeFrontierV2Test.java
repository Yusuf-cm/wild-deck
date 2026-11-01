package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyVerifiedLakeFrontierV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUTLET=at(0,2);
    private static final GlobalHydrologyAddressV2.Sample PIT=at(4,2);

    private static List<HydrologyGlobalSlopeRouterV2.Source> sphere() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return sources;
    }

    private static HydrologySphericalLakeBasinsV2.Plan plan() {
        return HydrologySphericalLakeBasinsV2.extract(sphere(),
            s->s.equals(OUTLET)?0:s.equals(PIT)?-5:10,
            Set.of(OUTLET),1000);
    }

    private static String initial() {
        return HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("initial",Map.of()));
    }

    @Test void verifiedSpillEdgeQueuesOnlyActualOverflowAndSurvivesRestart()
            throws IOException {
        var plan=plan();
        assertEquals(1,plan.lakes().size());
        var lake=plan.lakes().get(0);
        double capacity=lake.reservoir().capacity();
        double area=capacity/3;
        var runner=HydrologyWorldRunnerV2.open(
            directory.resolve("verified.chk"),0,initial());
        var first=HydrologyVerifiedLakeFrontierV2.advance(
            runner,"wet",plan,Map.of(lake.anchor(),
                new HydrologyVerifiedLakeFrontierV2.Forcing(
                    area,new SeasonalLakeCycleV2.Period(4,0,0,1))),4);
        assertEquals(1,runner.epoch());
        assertEquals(1,first.routed().queuedTransfers());
        assertEquals(capacity,HydrologyLakeStateV2.decode(runner.worldState())
            .volumes().get(lake.anchor()),capacity*1e-10);
        double expected=capacity/3;
        assertEquals(expected,runner.receiptBudget().pendingVolume(),expected*1e-9);
        var key=runner.inboxSnapshot().receipts().keySet().iterator().next();
        assertEquals(lake.spillTarget(),key.to());
        assertTrue(lake.cells().contains(key.from()));
        // A frontier export at epoch 1 is durably delivered in epoch 2.
        assertEquals(2,key.epoch());

        var restarted=HydrologyWorldRunnerV2.open(
            directory.resolve("verified.chk"),0,"ignored");
        var received=restarted.advanceAndQueueKnownFrontier(
            restarted.worldState(),List.of(List.of(
                new HydrologyFlowGraphV2.Node(lake.spillTarget(),0,null,true))));
        assertEquals(expected,received.committed().flow()
            .terminalOutflow().get(lake.spillTarget()),expected*1e-9);
        assertEquals(0,restarted.receiptBudget().pendingVolume(),1e-9);
        assertEquals(expected,restarted.receiptBudget().deliveredVolume(),expected*1e-9);
        var again=HydrologyWorldRunnerV2.open(
            directory.resolve("verified.chk"),0,"ignored");
        var dry=again.advanceAndQueueKnownFrontier(
            again.worldState(),List.of(List.of(
                new HydrologyFlowGraphV2.Node(lake.spillTarget(),0,null,true))));
        assertEquals(0,dry.committed().flow()
            .terminalOutflow().get(lake.spillTarget()),0);
    }

    @Test void planOrderingCannotChangeSpillReceiptIdentity()
            throws IOException {
        var original=plan();
        var reversed=new ArrayList<>(original.routes());
        Collections.reverse(reversed);
        var reordered=new HydrologySphericalLakeBasinsV2.Plan(
            reversed,original.lakes());
        var lake=original.lakes().get(0);
        var forcing=Map.of(lake.anchor(),
            new HydrologyVerifiedLakeFrontierV2.Forcing(
                lake.reservoir().capacity()/3,
                new SeasonalLakeCycleV2.Period(4,0,0,1)));
        var a=HydrologyWorldRunnerV2.open(directory.resolve("a.chk"),0,initial());
        var b=HydrologyWorldRunnerV2.open(directory.resolve("b.chk"),0,initial());
        HydrologyVerifiedLakeFrontierV2.advance(a,"wet",original,forcing,3);
        HydrologyVerifiedLakeFrontierV2.advance(b,"wet",reordered,forcing,3);
        assertEquals(1,a.inboxSnapshot().receipts().size());
        assertEquals(a.inboxSnapshot().receipts(),b.inboxSnapshot().receipts());
        assertEquals(a.worldState(),b.worldState());
    }

    @Test void incompletePlanAndDuplicatedRainfallFailWithoutMutation()
            throws IOException {
        var original=plan();
        var lake=original.lakes().get(0);
        var forcing=Map.of(lake.anchor(),
            new HydrologyVerifiedLakeFrontierV2.Forcing(10,
                new SeasonalLakeCycleV2.Period(1,0,0,1)));
        var runner=HydrologyWorldRunnerV2.open(
            directory.resolve("invalid.chk"),0,initial());
        var partial=new HydrologySphericalLakeBasinsV2.Plan(
            original.routes().subList(1,original.routes().size()),
            original.lakes());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyVerifiedLakeFrontierV2.advance(
                runner,"bad",partial,forcing,1));
        var rain=new ArrayList<>(original.routes());
        var first=rain.get(0);
        rain.set(0,new HydrologyFlowGraphV2.Node(
            first.address(),1,first.downstream(),first.terminal()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyVerifiedLakeFrontierV2.advance(runner,"bad",
                new HydrologySphericalLakeBasinsV2.Plan(rain,original.lakes()),
                forcing,1));
        assertEquals(0,runner.epoch());
        assertEquals("initial",HydrologyLakeStateV2.decode(
            runner.worldState()).world());
        assertTrue(runner.inboxSnapshot().receipts().isEmpty());
    }

    @Test void upstreamReceiptCannotBeSilentlyExcludedFromLakeStorage()
            throws IOException {
        var plan=plan();
        var lake=plan.lakes().get(0);
        var runner=HydrologyWorldRunnerV2.open(
            directory.resolve("incoming.chk"),0,initial());
        var upstream=at(3,2);
        assertTrue(HydrologySphericalTopologyV2.adjacent(upstream,PIT));
        runner.receive(new HydrologyTransferLedgerV2.Key(1,upstream,PIT),7);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyVerifiedLakeFrontierV2.advance(runner,"bad",plan,
                Map.of(lake.anchor(),new HydrologyVerifiedLakeFrontierV2.Forcing(
                    10,new SeasonalLakeCycleV2.Period(1,0,0,1))),1));
        assertEquals(0,runner.epoch());
        assertEquals(7,runner.receiptBudget().pendingVolume(),1e-9);
    }
}
