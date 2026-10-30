package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Persistent snow/soil and coupled river/lake budgets in one checkpoint. */
class HydrologySeasonalLakeNetworkV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(1,2);
    private static final GlobalHydrologyAddressV2.Sample B=at(2,2);
    private static final GlobalHydrologyAddressV2.Sample C=at(3,2);
    private static final GlobalHydrologyAddressV2.Sample D=at(4,2);

    private static HydrologySphericalLakeBasinsV2.Plan planet(boolean chain) {
        var routes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(long y=0;y<=4;y++) {
            long count=y==0||y==4?1:8;
            for(long x=0;x<count;x++) {
                var address=at(x,y);
                GlobalHydrologyAddressV2.Sample next=null;
                if(address.equals(A))next=B;
                if(chain && address.equals(B))next=C;
                if(chain && address.equals(C))next=D;
                routes.add(new HydrologyFlowGraphV2.Node(
                    address,0,next,next==null));
            }
        }
        assertEquals(26,routes.size());
        var basin=new LakeReservoirV2.Basin(
            new double[]{0},new double[]{1},5);
        var lakes=new ArrayList<HydrologySphericalLakeBasinsV2.Lake>();
        lakes.add(new HydrologySphericalLakeBasinsV2.Lake(
            A,List.of(A),B,chain?D:B,basin));
        if(chain)lakes.add(new HydrologySphericalLakeBasinsV2.Lake(
            C,List.of(C),D,D,basin));
        return new HydrologySphericalLakeBasinsV2.Plan(routes,lakes);
    }

    private static HydrologySnowSoilStateV2.Forcing weather(
            double precipitation,double temperature) {
        return new HydrologySnowSoilStateV2.Forcing(
            precipitation,temperature,0,0,1,10,1);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
                       HydrologyLakeNetworkStepV2.Forcing> lakes(boolean chain,
                                                               double evaporation) {
        var result=new HashMap<GlobalHydrologyAddressV2.Sample,
                               HydrologyLakeNetworkStepV2.Forcing>();
        result.put(A,new HydrologyLakeNetworkStepV2.Forcing(0,evaporation,4));
        if(chain)result.put(C,
            new HydrologyLakeNetworkStepV2.Forcing(0,evaporation,4));
        return result;
    }

    @Test void snowmeltAndIncomingReceiptsFillLakeExactlyOnceAfterRestart()
            throws IOException {
        var path=directory.resolve("seasonal.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,
            HydrologySeasonalLakeNetworkV2.initialState("initial"));
        var winter=HydrologySeasonalLakeNetworkV2.advance(
            runner,"winter",planet(false),
            Map.of(A,weather(3,-2),B,weather(2,2)),lakes(false,0));
        assertEquals(3,winter.snowSoil().storedVolume(),1e-10);
        assertEquals(2,winter.budget().terminalDischarge(),1e-10);
        assertEquals(0,winter.budget().finalLakes(),1e-10);
        assertEquals(0,winter.budget().residual(),1e-10);

        var key=new HydrologyTransferLedgerV2.Key(2,at(0,2),A);
        assertTrue(runner.receive(key,3));
        assertFalse(runner.receive(key,3));
        var recovered=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var spring=HydrologySeasonalLakeNetworkV2.advance(
            recovered,"spring",planet(false),
            Map.of(A,weather(4,2),B,weather(0,2)),lakes(false,0));
        // Snowpack 3 + new rain 4 + incoming river 3 = 10.
        // Lake holds 5, discharges 5, and the receipt is consumed once.
        assertEquals(10,spring.lakes().lakeInflow().get(A),1e-10);
        assertEquals(5,spring.lakes().lakeOverflow().get(A),1e-10);
        assertEquals(3,spring.budget().receivedRiverWater(),1e-10);
        assertEquals(5,spring.budget().finalLakes(),1e-10);
        assertEquals(0,spring.budget().residual(),1e-10);
        assertEquals(0,recovered.receiptBudget().pendingVolume(),1e-10);
        assertEquals(3,recovered.receiptBudget().deliveredVolume(),1e-10);

        var reopened=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var stored=HydrologyLakeStateV2.decode(reopened.worldState());
        var snow=HydrologySnowSoilStateV2.decode(stored.world());
        assertEquals("spring",snow.world());
        assertEquals(0,snow.cells().get(A).snowDepth(),1e-10);
        assertEquals(5,stored.volumes().get(A),1e-10);
        var dry=HydrologySeasonalLakeNetworkV2.advance(
            reopened,"dry",planet(false),
            Map.of(A,weather(0,2),B,weather(0,2)),lakes(false,1));
        assertEquals(0,dry.budget().receivedRiverWater(),1e-10);
        assertEquals(0,dry.budget().terminalDischarge(),1e-10);
        assertEquals(4,dry.budget().finalLakes(),1e-10);
        assertEquals(1,dry.budget().lakeEvaporation(),1e-10);
        assertEquals(0,dry.budget().residual(),1e-10);
    }

    @Test void twoLakeChainRoutesSeasonalRunoffThroughBothReservoirs()
            throws IOException {
        var runner=HydrologyWorldRunnerV2.open(
            directory.resolve("chain.chk"),0,
            HydrologySeasonalLakeNetworkV2.initialState("start"));
        var forcing=Map.of(A,weather(12,2),B,weather(2,2),
                           C,weather(3,2),D,weather(4,2));
        var first=HydrologySeasonalLakeNetworkV2.advance(
            runner,"rain",planet(true),forcing,lakes(true,0));
        assertEquals(21,first.snowSoil().runoffVolume(),1e-10);
        assertEquals(12,first.lakes().lakeInflow().get(A),1e-10);
        assertEquals(12,first.lakes().lakeInflow().get(C),1e-10);
        assertEquals(7,first.lakes().lakeOverflow().get(A),1e-10);
        assertEquals(7,first.lakes().lakeOverflow().get(C),1e-10);
        assertEquals(11,first.budget().terminalDischarge(),1e-10);
        assertEquals(10,first.budget().finalLakes(),1e-10);
        assertEquals(0,first.budget().residual(),1e-10);
        var restarted=HydrologyWorldRunnerV2.open(
            directory.resolve("chain.chk"),0,"ignored");
        var dry=Map.of(A,weather(0,2),B,weather(0,2),
                       C,weather(0,2),D,weather(0,2));
        var second=HydrologySeasonalLakeNetworkV2.advance(
            restarted,"dry",planet(true),dry,lakes(true,1));
        assertEquals(2,second.budget().lakeEvaporation(),1e-10);
        assertEquals(8,second.budget().finalLakes(),1e-10);
        assertEquals(0,second.budget().terminalDischarge(),1e-10);
        assertEquals(0,second.budget().residual(),1e-10);
    }

    @Test void invalidDoubleOwnershipAndPartialPlanetNeverConsumeReceipts()
            throws IOException {
        var runner=HydrologyWorldRunnerV2.open(
            directory.resolve("reject.chk"),0,
            HydrologySeasonalLakeNetworkV2.initialState("initial"));
        var key=new HydrologyTransferLedgerV2.Key(1,at(0,2),A);
        runner.receive(key,3);
        var original=planet(false);
        var duplicated=new ArrayList<>(original.routes());
        duplicated.set(0,new HydrologyFlowGraphV2.Node(
            duplicated.get(0).address(),1,null,true));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalLakeNetworkV2.advance(runner,"bad",
                new HydrologySphericalLakeBasinsV2.Plan(duplicated,original.lakes()),
                Map.of(A,weather(2,2)),lakes(false,0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalLakeNetworkV2.advance(runner,"bad",
                original,Map.of(A,weather(2,2)),
                Map.of(A,new HydrologyLakeNetworkStepV2.Forcing(1,0,1))));
        var partial=new ArrayList<>(original.routes());
        partial.remove(partial.size()-1);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalLakeNetworkV2.advance(runner,"bad",
                new HydrologySphericalLakeBasinsV2.Plan(partial,original.lakes()),
                Map.of(A,weather(2,2)),lakes(false,0)));
        assertEquals(0,runner.epoch());
        assertEquals(3,runner.receiptBudget().pendingVolume(),1e-10);
        assertEquals("initial",HydrologySnowSoilStateV2.decode(
            HydrologyLakeStateV2.decode(runner.worldState()).world()).world());
    }
    @Test void datelineAndPolarRunoffHaveStableCanonicalOwnership()
            throws IOException {
        var base=planet(false);
        var pole=at(0,0);
        var north=at(0,1);
        var seam=at(7,2);
        var meridian=at(0,2);
        assertTrue(HydrologySphericalTopologyV2.adjacent(pole,north));
        assertTrue(HydrologySphericalTopologyV2.adjacent(seam,meridian));
        var routes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:base.routes()) {
            var address=node.address();
            GlobalHydrologyAddressV2.Sample next=null;
            if(address.equals(pole))next=north;
            if(address.equals(north)||address.equals(seam))next=meridian;
            if(address.equals(meridian))next=A;
            if(address.equals(A))next=B;
            routes.add(new HydrologyFlowGraphV2.Node(
                address,0,next,next==null));
        }
        var plan=new HydrologySphericalLakeBasinsV2.Plan(routes,base.lakes());
        var reversed=new ArrayList<>(routes);
        Collections.reverse(reversed);
        var reorder=new HydrologySphericalLakeBasinsV2.Plan(
            reversed,base.lakes());
        var weather=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                      HydrologySnowSoilStateV2.Forcing>();
        weather.put(seam,weather(4,2));
        weather.put(pole,weather(3,2));
        var first=HydrologyWorldRunnerV2.open(
            directory.resolve("seam-a.chk"),0,
            HydrologySeasonalLakeNetworkV2.initialState("start"));
        var second=HydrologyWorldRunnerV2.open(
            directory.resolve("seam-b.chk"),0,
            HydrologySeasonalLakeNetworkV2.initialState("start"));
        var a=HydrologySeasonalLakeNetworkV2.advance(
            first,"season",plan,weather,lakes(false,0));
        var b=HydrologySeasonalLakeNetworkV2.advance(
            second,"season",reorder,weather,lakes(false,0));
        assertEquals(7,a.snowSoil().runoffVolume(),1e-10);
        assertEquals(7,a.lakes().lakeInflow().get(A),1e-10);
        assertEquals(5,a.budget().finalLakes(),1e-10);
        assertEquals(2,a.budget().terminalDischarge(),1e-10);
        assertEquals(0,a.budget().residual(),1e-10);
        assertEquals(a.budget(),b.budget());
        assertEquals(first.worldState(),second.worldState());
        assertEquals(first.inboxSnapshot(),second.inboxSnapshot());
        assertEquals(26,plan.routes().size());
    }

}
