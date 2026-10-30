package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeNetworkStepV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample A=at(1,2);
    private static final GlobalHydrologyAddressV2.Sample B=at(2,2);
    private static final GlobalHydrologyAddressV2.Sample C=at(3,2);
    private static final GlobalHydrologyAddressV2.Sample D=at(4,2);

    private static HydrologySphericalLakeBasinsV2.Plan chain(
            double a,double b,double c,double d) {
        var routes=new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(A,a,B,false),
            new HydrologyFlowGraphV2.Node(B,b,C,false),
            new HydrologyFlowGraphV2.Node(C,c,D,false),
            new HydrologyFlowGraphV2.Node(D,d,null,true)));
        // Include the complete 26-vertex sphere. The other zero-flow
        // samples are explicitly verified terminals in this synthetic graph.
        var all=new ArrayList<GlobalHydrologyAddressV2.Sample>();
        all.add(at(0,0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)all.add(at(x,y));
        all.add(at(0,4));
        for(var address:all)
            if(!Set.of(A,B,C,D).contains(address))
                routes.add(new HydrologyFlowGraphV2.Node(
                    address,0,null,true));
        var basin=new LakeReservoirV2.Basin(new double[]{0},
            new double[]{1},5);
        return new HydrologySphericalLakeBasinsV2.Plan(routes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(
                A,List.of(A),B,D,basin),
            new HydrologySphericalLakeBasinsV2.Lake(
                C,List.of(C),D,D,basin)));
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
                       HydrologyLakeNetworkStepV2.Forcing> dryForcing() {
        return Map.of(A,new HydrologyLakeNetworkStepV2.Forcing(0,0,4),
                      C,new HydrologyLakeNetworkStepV2.Forcing(0,0,4));
    }

    @Test void chainedLakesInterceptRiverWaterAndReleaseOnlyActualOverflow() {
        var plan=chain(12,2,3,4);
        var initial=new HydrologyLakeStateV2.State("world",Map.of());
        var first=HydrologyLakeNetworkStepV2.step(plan,initial,dryForcing());
        assertEquals(12,first.lakeInflow().get(A),1e-10);
        assertEquals(12,first.lakeInflow().get(C),1e-10);
        assertEquals(7,first.lakeOverflow().get(A),1e-10);
        assertEquals(7,first.lakeOverflow().get(C),1e-10);
        assertEquals(11,first.terminalOutflow().get(D),1e-10);
        assertEquals(5,first.state().volumes().get(A),1e-10);
        assertEquals(5,first.state().volumes().get(C),1e-10);
        assertEquals(21,first.budget().sourceWater(),1e-10);
        assertEquals(10,first.budget().finalStorage(),1e-10);
        assertEquals(0,first.budget().residual(),1e-10);

        var reversedRoutes=new ArrayList<>(plan.routes());
        var reversedLakes=new ArrayList<>(plan.lakes());
        Collections.reverse(reversedRoutes);
        Collections.reverse(reversedLakes);
        var reordered=HydrologyLakeNetworkStepV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(reversedRoutes,reversedLakes),
            initial,dryForcing());
        assertEquals(first,reordered);

        var restored=HydrologyLakeStateV2.decode(
            HydrologyLakeStateV2.encode(first.state()));
        var nextForcing=Map.of(
            A,new HydrologyLakeNetworkStepV2.Forcing(0,1,4),
            C,new HydrologyLakeNetworkStepV2.Forcing(0,1,4));
        var second=HydrologyLakeNetworkStepV2.step(
            chain(0,0,0,0),restored,nextForcing);
        assertEquals(4,second.state().volumes().get(A),1e-10);
        assertEquals(4,second.state().volumes().get(C),1e-10);
        assertEquals(2,second.budget().evaporation(),1e-10);
        assertEquals(0,second.budget().terminalOutflow(),1e-10);
        assertEquals(0,second.budget().residual(),1e-10);
    }

    @Test void fullSphereLakeAndIndependentOutletWaterNeverDoubleCount() {
        var sources=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        sources.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        var pit=at(4,2);
        var outlet=at(0,2);
        var extracted=HydrologySphericalLakeBasinsV2.extract(
            sources,s->s.equals(outlet)?0:s.equals(pit)?-5:10,
            Set.of(outlet),1000);
        assertEquals(1,extracted.lakes().size());
        var lake=extracted.lakes().get(0);
        double capacity=lake.reservoir().capacity();
        var routes=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:extracted.routes())
            routes.add(new HydrologyFlowGraphV2.Node(node.address(),
                node.address().equals(pit)?capacity+10:
                node.address().equals(outlet)?4:0,
                node.downstream(),node.terminal()));
        var plan=new HydrologySphericalLakeBasinsV2.Plan(routes,extracted.lakes());
        var result=HydrologyLakeNetworkStepV2.step(plan,
            new HydrologyLakeStateV2.State("checkpoint",Map.of()),
            Map.of(lake.anchor(),new HydrologyLakeNetworkStepV2.Forcing(3,0,8)));
        assertEquals(capacity,result.state().volumes().get(pit),1e-6);
        assertEquals(13,result.lakeOverflow().get(pit),1e-6);
        assertEquals(17,result.terminalOutflow().get(outlet),1e-6);
        assertEquals(3,result.budget().additionalInflow(),1e-10);
        assertEquals(0,result.budget().residual(),1e-6);
        assertEquals("checkpoint",result.state().world());
        var noRain=new ArrayList<HydrologyFlowGraphV2.Node>();
        for(var node:extracted.routes())
            noRain.add(new HydrologyFlowGraphV2.Node(
                node.address(),0,node.downstream(),node.terminal()));
        var dry=HydrologyLakeNetworkStepV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(noRain,extracted.lakes()),
            new HydrologyLakeStateV2.State("checkpoint",Map.of()),
            Map.of(pit,new HydrologyLakeNetworkStepV2.Forcing(0,2,8)));
        assertEquals(0,dry.lakeOverflow().get(pit),0);
        assertEquals(0,dry.budget().evaporation(),0);
    }

    @Test void invalidIdentityUnknownDrainageAndDoubleOwnedCellsFailClosed() {
        var plan=chain(1,1,1,1);
        var state=new HydrologyLakeStateV2.State("world",Map.of());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeNetworkStepV2.step(plan,state,Map.of(
                A,new HydrologyLakeNetworkStepV2.Forcing(0,0,1))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeNetworkStepV2.step(plan,
                new HydrologyLakeStateV2.State("world",Map.of(B,1.0)),
                dryForcing()));
        var lake=plan.lakes().get(0);
        var overlapping=new HydrologySphericalLakeBasinsV2.Lake(
            C,List.of(C,A),D,D,
            new LakeReservoirV2.Basin(new double[]{0,0},new double[]{1,1},5));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeNetworkStepV2.step(
                new HydrologySphericalLakeBasinsV2.Plan(
                    plan.routes(),List.of(lake,overlapping)),state,dryForcing()));
        var badExit=new HydrologySphericalLakeBasinsV2.Lake(
            A,List.of(A),D,D,lake.reservoir());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeNetworkStepV2.step(
                new HydrologySphericalLakeBasinsV2.Plan(
                    plan.routes(),List.of(badExit,plan.lakes().get(1))),
                state,dryForcing()));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyLakeNetworkStepV2.Forcing(-1,0,1));
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyLakeNetworkStepV2.Forcing(0,0,0));
    }

    @Test void partialSphereCannotMasqueradeAsAuthoritativeLakeNetwork() {
        var plan=chain(1,1,1,1);
        var partial=new ArrayList<>(plan.routes());
        partial.removeIf(node->node.address().equals(at(7,2)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakeNetworkStepV2.step(
                new HydrologySphericalLakeBasinsV2.Plan(
                    partial,plan.lakes()),
                new HydrologyLakeStateV2.State("world",Map.of()),
                dryForcing()));
    }


    @Test void polarAndDatelineLakesConvergeWithoutDuplicatingSharedVertices() {
        var pole=at(0,0);
        var seam=at(7,1);
        var join=at(0,1);
        var outlet=at(0,2);
        var routes=new ArrayList<HydrologyFlowGraphV2.Node>();
        routes.add(new HydrologyFlowGraphV2.Node(pole,9,join,false));
        routes.add(new HydrologyFlowGraphV2.Node(seam,8,join,false));
        routes.add(new HydrologyFlowGraphV2.Node(join,2,outlet,false));
        routes.add(new HydrologyFlowGraphV2.Node(outlet,1,null,true));
        for(long y=0;y<=4;y++) {
            long count=y==0||y==4?1:8;
            for(long x=0;x<count;x++) {
                var address=at(x,y);
                if(!Set.of(pole,seam,join,outlet).contains(address))
                    routes.add(new HydrologyFlowGraphV2.Node(
                        address,0,null,true));
            }
        }
        assertEquals(26,routes.size());
        assertTrue(HydrologySphericalTopologyV2.adjacent(pole,join));
        assertTrue(HydrologySphericalTopologyV2.adjacent(seam,join));
        var basin=new LakeReservoirV2.Basin(
            new double[]{0},new double[]{1},5);
        var plan=new HydrologySphericalLakeBasinsV2.Plan(routes,List.of(
            new HydrologySphericalLakeBasinsV2.Lake(
                pole,List.of(pole),join,outlet,basin),
            new HydrologySphericalLakeBasinsV2.Lake(
                seam,List.of(seam),join,outlet,basin)));
        var result=HydrologyLakeNetworkStepV2.step(plan,
            new HydrologyLakeStateV2.State("planet",Map.of()),
            Map.of(pole,new HydrologyLakeNetworkStepV2.Forcing(0,0,2),
                   seam,new HydrologyLakeNetworkStepV2.Forcing(0,0,2)));
        assertEquals(4,result.lakeOverflow().get(pole),1e-10);
        assertEquals(3,result.lakeOverflow().get(seam),1e-10);
        assertEquals(10,result.terminalOutflow().get(outlet),1e-10);
        assertEquals(10,result.budget().finalStorage(),1e-10);
        assertEquals(20,result.budget().sourceWater(),1e-10);
        assertEquals(0,result.budget().residual(),1e-10);
    }

}
