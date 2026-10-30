package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A second runner may receive water after the first runner opens. Advancing
 * from that stale snapshot must never overwrite its already durable receipts.
 */
class HydrologyStaleRunnerConservationV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }

    private static List<HydrologyFlowGraphV2.Node> receiver() {
        return List.of(
            new HydrologyFlowGraphV2.Node(at(3,2),0,at(4,2),false),
            new HydrologyFlowGraphV2.Node(at(4,2),0,null,true));
    }

    @Test void staleOrdinaryEpochCannotEraseIncomingReceipt() throws Exception {
        Path path=directory.resolve("ordinary.chk");
        var stale=HydrologyWorldRunnerV2.open(path,0,"world");
        var writer=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var key=new HydrologyTransferLedgerV2.Key(1,at(2,2),at(3,2));
        assertTrue(writer.receive(key,7));
        assertThrows(IOException.class,()->
            stale.advance("stale",List.of(receiver())));
        assertEquals(0,stale.epoch());
        var fresh=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(7,fresh.receiptBudget().pendingVolume(),1e-12);
        assertEquals(7,fresh.advance("committed",List.of(receiver()))
            .terminalOutflow().get(at(4,2)),1e-12);
        assertEquals(0,HydrologyWorldRunnerV2.open(path,0,"ignored")
            .receiptBudget().pendingVolume(),1e-12);
    }

    @Test void staleFrontierEpochCannotEraseIncomingReceipt() throws Exception {
        Path path=directory.resolve("frontier.chk");
        var stale=HydrologyWorldRunnerV2.open(path,0,"world");
        var writer=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var key=new HydrologyTransferLedgerV2.Key(1,at(2,2),at(3,2));
        writer.receive(key,5);
        assertThrows(IOException.class,()->
            stale.advanceAndQueueKnownFrontier("stale",List.of(receiver())));
        var fresh=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(5,fresh.advanceAndQueueKnownFrontier(
            "committed",List.of(receiver())).committed().flow()
            .terminalOutflow().get(at(4,2)),1e-12);
        assertEquals(1,HydrologyWorldRunnerV2.open(path,0,"ignored").epoch());
    }

    @Test void staleLakeEpochCannotEraseIncomingReceipt() throws Exception {
        Path path=directory.resolve("lake.chk");
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        var outlet=at(0,2);
        var atlas=HydrologySphericalRouteAtlasV2.compile(
            sources,a->a.equals(outlet)?0:10,Set.of(outlet));
        var plan=new HydrologySphericalLakeBasinsV2.Plan(atlas.routes(),List.of());
        var initial=HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("world",Map.of()));
        var stale=HydrologyWorldRunnerV2.open(path,0,initial);
        var writer=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var from=at(4,2);
        var key=new HydrologyTransferLedgerV2.Key(
            1,from,atlas.routeAt(from).downstream());
        writer.receive(key,9);
        assertThrows(IOException.class,()->
            stale.advanceClosedSphericalLakeNetwork("stale",plan,Map.of()));
        assertEquals(0,stale.epoch());
        var fresh=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(9,fresh.receiptBudget().pendingVolume(),1e-12);
        var result=fresh.advanceClosedSphericalLakeNetwork("committed",plan,Map.of());
        assertEquals(9,result.budget().terminalOutflow(),1e-12);
        assertEquals(0,HydrologyWorldRunnerV2.open(path,0,"ignored")
            .receiptBudget().pendingVolume(),1e-12);
    }

    @Test void staleReceiptWriterMustReopenBeforeAppending() throws Exception {
        Path path=directory.resolve("receiving.chk");
        var stale=HydrologyWorldRunnerV2.open(path,0,"world");
        var writer=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var first=new HydrologyTransferLedgerV2.Key(1,at(2,2),at(3,2));
        var second=new HydrologyTransferLedgerV2.Key(1,at(2,1),at(3,2));
        writer.receive(first,3);
        assertThrows(IOException.class,()->stale.receive(second,5));
        assertEquals(0,stale.receiptBudget().pendingVolume(),1e-12);
        var fresh=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertTrue(fresh.receive(second,5));
        assertEquals(8,fresh.receiptBudget().pendingVolume(),1e-12);
        assertEquals(8,fresh.advance("committed",List.of(receiver()))
            .terminalOutflow().get(at(4,2)),1e-12);
    }
}
