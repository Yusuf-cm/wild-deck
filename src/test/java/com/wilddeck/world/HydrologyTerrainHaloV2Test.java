package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTerrainHaloV2Test {
    private static double total(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    @Test void haloSuppliesRealRoutesOutsideCoreWithoutDuplicatingRainfall() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),20261029);
        var climate=new PlanetClimateV2(terrain);
        var first=HydrologyTerrainWindowV2.generateCoreWithHalo(
            terrain,climate,2,1,1,2,1,4,1,1);
        var second=HydrologyTerrainWindowV2.generateCoreWithHalo(
            terrain,climate,2,1,1,2,1,4,1,1);
        assertEquals(first.coreNodes(),second.coreNodes());
        assertEquals(45,first.coreNodes().size()); // (2*4+1)*(1*4+1)
        var addresses=new HashSet<GlobalHydrologyAddressV2.Sample>();
        double local=0;
        for(var node:first.coreNodes()) {
            assertTrue(addresses.add(node.address()));
            assertTrue(node.address().x()>=4&&node.address().x()<=12);
            assertTrue(node.address().y()>=4&&node.address().y()<=8);
            assertTrue(node.terminal()||node.downstream()!=null,
                "Halo must resolve every core vertex not at the planet edge");
            local+=node.localWater();
        }
        var solved=HydrologyFlowGraphV2.solve(first.coreNodes());
        assertEquals(local,total(solved.terminalOutflow().values())+
            total(solved.pendingOutflow().values()),1e-6);
        assertTrue(solved.pendingOutflow().keySet().stream().noneMatch(addresses::contains));
    }

    @Test void clippingHaloAtPlanetBoundaryDoesNotInventOceanOutlet() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),77);
        var climate=new PlanetClimateV2(terrain);
        var core=HydrologyTerrainWindowV2.generateCoreWithHalo(
            terrain,climate,2,0,0,1,1,4,1,1);
        assertEquals(25,core.coreNodes().size());
        assertEquals(25,new HashSet<>(core.coreNodes().stream()
            .map(HydrologyFlowGraphV2.Node::address).toList()).size());
        for(var node:core.coreNodes()) {
            if(!node.terminal()&&node.downstream()==null)
                assertTrue(node.address().x()==0||node.address().y()==0,
                    "Only a true planet-edge vertex may remain unresolved");
        }
    }

    @Test void generatedCoreAdvancesDurablyAndQueuesRealHaloFrontier() throws Exception {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),20261029);
        var climate=new PlanetClimateV2(terrain);
        var path=java.nio.file.Files.createTempFile("wilddeck-halo-",".chk");
        try {
            java.nio.file.Files.delete(path);
            var runner=HydrologyWorldRunnerV2.open(path,0,"initial");
            var routed=runner.advanceTerrainCoreAndQueue(
                "seeded-core",terrain,climate,2,1,1,2,1,4,1,1);
            assertTrue(routed.queuedTransfers()>0);
            assertTrue(routed.committed().flow().pendingOutflow().size()>0);
            assertEquals(1,runner.epoch());
            var recovered=HydrologyWorldRunnerV2.open(path,0,"ignored");
            assertEquals("seeded-core",recovered.worldState());
            assertFalse(recovered.pendingWaterRegions().isEmpty());
            assertEquals(routed.queuedTransfers(),recovered.inboxSnapshot().receipts().size());
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
    }

    @Test void rejectsInvalidCoreBoundsAndHalo() {
        var terrain=new PlanetSurfaceV2(Planet.emptyEarthSized(),77);
        var climate=new PlanetClimateV2(terrain);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTerrainWindowV2.generateCoreWithHalo(
                terrain,climate,2,7,3,2,1,4,1,1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyTerrainWindowV2.generateCoreWithHalo(
                terrain,climate,2,1,1,1,1,4,-1,1));
    }
}
