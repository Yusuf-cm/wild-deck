package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyEndToEndV2Test {
    private static double sum(double[] values) {
        double total=0;for(double value:values)total+=value;return total;
    }
    private static double sum(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    @Test void seededMosaicPreservesWaterAndSedimentAcrossSubsystems() {
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),8675309);
        var climate=new PlanetClimateV2(world);
        var hydrology=RegionalHydrologyV2.generate(world,climate,2,1,1,2,2,4,1);

        var graph=HydrologyFlowGraphV2.solve(
            HydrologyGraphAdapterV2.nodes(hydrology,2,4,1,1));
        double rainfall=sum(hydrology.terrain().rainfall());
        assertEquals(rainfall,
            sum(graph.terminalOutflow().values())+sum(graph.pendingOutflow().values()),1e-6);

        var snapshot=HydrologyFrontierCodecV2.encode(graph.pendingOutflow());
        assertEquals(graph.pendingOutflow(),HydrologyFrontierCodecV2.decode(snapshot));

        double[] elevation=hydrology.terrain().elevation();
        double[] area=new double[elevation.length];Arrays.fill(area,1);
        var basins=LakeBasinExtractorV2.extract(hydrology.lakes(),elevation,area);
        for(var basin:basins){
            assertTrue(basin.reservoir().capacity()>0);
            var step=LakeReservoirV2.step(basin.reservoir(),0,1,0);
            assertEquals(1,step.volume()+step.overflow(),1e-9);
        }

        var eroded=RiverErosionV2.erode(elevation,hydrology.lakes(),
                                         hydrology.spillDischarge(),0.05,0.5);
        var sedimentMass=SedimentBudgetV2.erodedMass(elevation,eroded,area,2000);
        double[] capacity=new double[elevation.length];Arrays.fill(capacity,100000);
        var sediment=SedimentBudgetV2.route(hydrology.lakes(),sedimentMass,capacity);
        assertEquals(sum(sedimentMass),
                     sum(sediment.deposited())+sum(sediment.outletExport()),1e-6);
    }
}
