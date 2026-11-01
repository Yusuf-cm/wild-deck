package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedLakeBasinIntegrationV2Test {
    @Test void boundedBasinFillsEvaporatesAndSpillsToVerifiedOutletWithoutWaterCreation(){
        double[] terrain={0,8,8,8,0,8,8,8,5};
        double[] area={1,1,1,1,1,1,1,1,1};
        var potential=LakeSpillV2.fillToVerifiedOutlets(
            3,3,terrain,Set.of(8));
        var lakes=LakeBasinExtractorV2.extract(potential,terrain,area);
        assertEquals(1,lakes.size());
        assertEquals(List.of(0,4),lakes.get(0).cells());
        assertEquals(10,lakes.get(0).reservoir().capacity(),1e-9);

        var periods=List.of(
            new SeasonalLakeCycleV2.Period(10,0,0,1),
            new SeasonalLakeCycleV2.Period(0,5,0,1));
        var simulated=SeasonalLakeCycleV2.simulateSubsteps(
            lakes.get(0).reservoir(),0,2,periods,10);
        assertEquals(20,simulated.totalInput(),1e-9);
        assertEquals(10,simulated.totalOverflow(),1e-9);
        assertEquals(10,simulated.totalEvaporation(),1e-9);
        assertEquals(0,simulated.finalVolume(),1e-9);
        assertEquals(simulated.initialVolume()+simulated.totalInput(),
            simulated.finalVolume()+simulated.totalEvaporation()+
                simulated.totalOverflow(),1e-8);

        var upstream=new GlobalHydrologyAddressV2.Sample(2,4,1,1);
        var outlet=new GlobalHydrologyAddressV2.Sample(2,4,2,2);
        var routed=HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(
                upstream,simulated.totalOverflow(),outlet,false),
            new HydrologyFlowGraphV2.Node(outlet,0,null,true)));
        assertEquals(10,routed.terminalOutflow().get(outlet),1e-9);
        assertTrue(routed.pendingOutflow().isEmpty());
    }
}
