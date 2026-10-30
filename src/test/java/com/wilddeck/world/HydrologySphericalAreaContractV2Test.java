package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySphericalAreaContractV2Test {
    private static final double RADIUS=1000;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample OUT=at(0,2),PIT=at(4,2);

    private static List<HydrologyGlobalSlopeRouterV2.Source> sources() {
        var result=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,0),0));
        for(long y=1;y<4;y++)for(long x=0;x<8;x++)
            result.add(new HydrologyGlobalSlopeRouterV2.Source(at(x,y),0));
        result.add(new HydrologyGlobalSlopeRouterV2.Source(at(0,4),0));
        return result;
    }
    private static HydrologyGlobalSlopeRouterV2.Elevations terrain(double pit) {
        return a->a.equals(OUT)?0:a.equals(PIT)?pit:10;
    }
    private static HydrologySphericalRouteAtlasV2 atlas(double pit) {
        return HydrologySphericalRouteAtlasV2.compile(
            sources(),terrain(pit),Set.of(OUT));
    }
    private static HydrologySphericalLakeBasinsV2.Plan plan(double pit,
                                                              double radius) {
        return HydrologySphericalLakeBasinsV2.extract(
            sources(),terrain(pit),Set.of(OUT),radius);
    }
    private static HydrologySnowSoilStateV2.Forcing weather(double area) {
        return new HydrologySnowSoilStateV2.Forcing(1,2,0,0,1,10,area);
    }
    private static Map<GlobalHydrologyAddressV2.Sample,
                       HydrologyLakeNetworkStepV2.Forcing> lakeForcing() {
        return Map.of(PIT,new HydrologyLakeNetworkStepV2.Forcing(0,0,1));
    }
    private static String seasonalLakeState(double volume) {
        var snow=HydrologySnowSoilStateV2.encode(
            new HydrologySnowSoilStateV2.State("start",Map.of()));
        return HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State(snow,Map.of(PIT,volume)));
    }

    @Test void validatesCanonicalPolarAndLakeAreaOwnership() {
        var plan=plan(-5,RADIUS);
        assertEquals(1,plan.lakes().size());
        assertDoesNotThrow(()->HydrologySphericalAreaContractV2.verifyPlan(plan,RADIUS));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAreaContractV2.verifyPlan(plan,RADIUS*2));
        var pole=at(0,0);
        var polar=HydrologySnowSoilStateV2.Forcing.forSphericalCell(
            pole,RADIUS,1,-2,0,0,1,10);
        assertDoesNotThrow(()->HydrologySphericalAreaContractV2.verifyWeather(
            Map.of(pole,polar),RADIUS));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAreaContractV2.verifyWeather(
                Map.of(pole,polar),RADIUS*2));
    }

    @Test void wrongSeasonalAreaFailsBeforeReceiptConsumption(@TempDir Path dir)
            throws Exception {
        var old=atlas(-5);
        var plan=plan(-5,RADIUS);
        var file=dir.resolve("area.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,
            HydrologyWetLakeDrainageActivationV2.bind(old,plan,
                seasonalLakeState(0)));
        var source=old.routes().stream().filter(n->!n.terminal()).findFirst().orElseThrow();
        var key=new HydrologyTransferLedgerV2.Key(
            1,source.address(),source.downstream());
        assertTrue(runner.receiveVerifiedDrainage(old,key,3));
        double area=HydrologySphericalCellAreaV2.areaAt(PIT,RADIUS);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAreaContractV2.advanceBound(
                runner,"bad",old,plan,Map.of(PIT,weather(area*2)),
                lakeForcing(),RADIUS));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAreaContractV2.advanceBound(
                runner,"bad",old,plan,Map.of(PIT,weather(area)),
                lakeForcing(),RADIUS*2));
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(0,restored.epoch());
        assertEquals(3,restored.receiptBudget().pendingVolume(),0);
        var result=HydrologySphericalAreaContractV2.advanceBound(
            restored,"rain",old,plan,Map.of(PIT,weather(area)),
            lakeForcing(),RADIUS);
        assertEquals(1,restored.epoch());
        assertEquals(3,result.budget().receivedRiverWater(),1e-8);
        assertEquals(0,result.budget().residual(),1e-7);
        assertEquals(0,restored.receiptBudget().pendingVolume(),0);
        assertTrue(HydrologyWorldRunnerV2.open(file,0,"ignored")
            .inboxSnapshot().delivered().contains(key));
    }

    @Test void transitionRejectsDifferentPlanetaryRadiusWithoutLosingWater(
            @TempDir Path dir) throws Exception {
        var old=atlas(-5);
        var oldPlan=plan(-5,RADIUS);
        var newPlan=plan(-4,RADIUS);
        var changedRadius=plan(-4,RADIUS*2);
        var audit=HydrologySphericalAtlasRebuildAuditV2.rebuild(
            old,sources(),terrain(-4),Set.of(OUT));
        var file=dir.resolve("radius.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,
            HydrologyWetLakeDrainageActivationV2.bind(
                old,oldPlan,seasonalLakeState(5)));
        var source=old.routes().stream().filter(n->!n.terminal()).findFirst().orElseThrow();
        var key=new HydrologyTransferLedgerV2.Key(
            1,source.address(),source.downstream());
        runner.receiveVerifiedDrainage(old,key,7);
        assertThrows(IllegalArgumentException.class,()->
            runner.activateSeasonalDisplacedWetLakeDrainage(
                old,audit,oldPlan,changedRadius));
        assertThrows(IllegalArgumentException.class,()->
            HydrologySphericalAreaContractV2.activateSeasonalDisplaced(
                runner,old,audit,oldPlan,newPlan,RADIUS*2));
        var restored=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(0,restored.epoch());
        assertEquals(7,restored.receiptBudget().pendingVolume(),0);
        var committed=HydrologySphericalAreaContractV2.activateSeasonalDisplaced(
            restored,old,audit,oldPlan,newPlan,RADIUS);
        assertEquals(1,restored.epoch());
        assertEquals(7,committed.landings().pendingVolume(),0);
        assertEquals(0,restored.receiptBudget().pendingVolume(),0);
        restored.verifyBoundWetLakeDrainage(audit.rebuilt(),newPlan);
    }
}
