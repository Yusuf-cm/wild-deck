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
                HydrologyGlobalSlopeRouterV2Test::height,Set.of(at(5,3))));
    }
}
