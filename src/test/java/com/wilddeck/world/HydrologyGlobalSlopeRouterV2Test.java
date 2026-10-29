package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyGlobalSlopeRouterV2Test {
    @TempDir Path directory;
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static double height(GlobalHydrologyAddressV2.Sample p) {
        return 20-p.x()+2*Math.abs(p.y()-3);
    }
    private static HydrologyGlobalSlopeRouterV2.Source source(long x,long y,double water) {
        return new HydrologyGlobalSlopeRouterV2.Source(at(x,y),water);
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> upstream() {
        return List.of(source(4,3,3),source(4,4,2));
    }
    private static List<HydrologyGlobalSlopeRouterV2.Source> downstream() {
        return List.of(source(5,3,1),source(6,3,0),source(7,3,0));
    }

    @Test void independentlyLoadedWindowsAgreeWithCombinedRouting() {
        var all=new ArrayList<>(upstream());
        all.addAll(downstream());
        var verified=Set.of(at(7,3));
        var combined=HydrologyGlobalSlopeRouterV2.route(all,
            HydrologyGlobalSlopeRouterV2Test::height,verified);
        var left=HydrologyGlobalSlopeRouterV2.route(upstream(),
            HydrologyGlobalSlopeRouterV2Test::height,Set.of());
        var right=HydrologyGlobalSlopeRouterV2.route(downstream(),
            HydrologyGlobalSlopeRouterV2Test::height,verified);
        var joined=new ArrayList<>(left);
        joined.addAll(right);
        joined.sort(Comparator.comparing(HydrologyFlowGraphV2.Node::address,
            HydrologyFlowGraphV2.ADDRESS_ORDER));
        assertEquals(combined,joined);
        assertEquals(at(5,3),left.get(0).downstream());
        assertEquals(at(5,3),left.get(1).downstream());
        assertEquals(6,HydrologyFlowGraphV2.solve(combined)
            .terminalOutflow().get(at(7,3)),1e-9);
        assertEquals(HydrologyRiverIdentityV2.knownReaches(combined),
            HydrologyRiverIdentityV2.knownReaches(joined));
        Collections.reverse(all);
        assertEquals(combined,HydrologyGlobalSlopeRouterV2.route(all,
            HydrologyGlobalSlopeRouterV2Test::height,verified));
    }

    @Test void twoUnloadedFrontierTransfersConserveAcrossRestart() throws IOException {
        var file=directory.resolve("slope.chk");
        var runner=HydrologyWorldRunnerV2.open(file,0,"initial");
        var left=HydrologyGlobalSlopeRouterV2.route(upstream(),
            HydrologyGlobalSlopeRouterV2Test::height,Set.of());
        var dispatched=runner.advanceAndQueueKnownFrontier("left",List.of(left));
        assertEquals(2,dispatched.queuedTransfers());
        assertEquals(5,dispatched.committed().flow()
            .pendingOutflow().get(at(5,3)),1e-9);
        var reloaded=HydrologyWorldRunnerV2.open(file,0,"ignored");
        var right=HydrologyGlobalSlopeRouterV2.route(downstream(),
            HydrologyGlobalSlopeRouterV2Test::height,Set.of(at(7,3)));
        assertEquals(6,reloaded.advanceAndQueueKnownFrontier("right",List.of(right))
            .committed().flow().terminalOutflow().get(at(7,3)),1e-9);
        var again=HydrologyWorldRunnerV2.open(file,0,"ignored");
        assertEquals(1,again.advance("new-epoch",List.of(right))
            .terminalOutflow().get(at(7,3)),1e-9);
        assertEquals(0,again.receiptBudget().pendingVolume(),1e-9);
    }

    @Test void seededPlanetElevationAgreesAtSharedRegionVertex() {
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),1234567);
        var lookup=HydrologyGlobalSlopeRouterV2.planetElevations(world);
        var left=new PlanetRegionGrid.Region(2,1,1);
        var right=new PlanetRegionGrid.Region(2,2,1);
        double a=world.sample(PlanetRegionGrid.point(left,4,2,4)).elevationMeters();
        double b=world.sample(PlanetRegionGrid.point(right,0,2,4)).elevationMeters();
        assertEquals(a,b,0);
        assertEquals(a,lookup.at(at(8,6)),0);
        assertEquals(lookup.at(at(8,6)),lookup.at(at(8,6)),0);
    }

    @Test void independentlyGeneratedInteriorRegionsOwnRainfallOnce() {
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),1234567);
        var climate=new PlanetClimateV2(world);
        var west=HydrologyGlobalSlopeRouterV2.seededOwnedInteriorRegion(
            climate,new PlanetRegionGrid.Region(2,1,1),4,10);
        var east=HydrologyGlobalSlopeRouterV2.seededOwnedInteriorRegion(
            climate,new PlanetRegionGrid.Region(2,2,1),4,10);
        assertEquals(16,west.size());
        assertEquals(16,east.size());
        var all=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>(west);
        all.addAll(east);
        assertEquals(32,new HashSet<>(all.stream().map(
            HydrologyGlobalSlopeRouterV2.Source::address).toList()).size());
        var elevation=HydrologyGlobalSlopeRouterV2.planetElevations(world);
        var nodes=HydrologyGlobalSlopeRouterV2.route(all,elevation,Set.of());
        var left=HydrologyGlobalSlopeRouterV2.route(west,elevation,Set.of());
        var right=HydrologyGlobalSlopeRouterV2.route(east,elevation,Set.of());
        var joined=new ArrayList<>(left);
        joined.addAll(right);
        joined.sort(Comparator.comparing(HydrologyFlowGraphV2.Node::address,
            HydrologyFlowGraphV2.ADDRESS_ORDER));
        assertEquals(nodes,joined);
        HydrologyConservationV2.verify(nodes,HydrologyFlowGraphV2.solve(nodes));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyGlobalSlopeRouterV2.seededOwnedInteriorRegion(
                climate,new PlanetRegionGrid.Region(2,0,1),4,10));
    }

    @Test void flatSinkStaysUnresolvedAndInvalidInputsFailClosed() {
        var flat=HydrologyGlobalSlopeRouterV2.route(
            List.of(source(4,3,4)),p->10,Set.of());
        assertNull(flat.get(0).downstream());
        assertFalse(flat.get(0).terminal());
        assertEquals(4,HydrologyFlowGraphV2.solve(flat)
            .pendingOutflow().get(at(4,3)),1e-9);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyGlobalSlopeRouterV2.route(List.of(source(4,3,1),source(4,3,2)),
                HydrologyGlobalSlopeRouterV2Test::height,Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyGlobalSlopeRouterV2.route(List.of(source(4,3,1)),
                p->Double.NaN,Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyGlobalSlopeRouterV2.route(List.of(source(4,3,1)),
                p->p.equals(at(4,3))?Double.MAX_VALUE:-Double.MAX_VALUE,
                Set.of()));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyGlobalSlopeRouterV2.route(List.of(source(4,3,1)),
                HydrologyGlobalSlopeRouterV2Test::height,Set.of(at(5,3))));
    }
}
