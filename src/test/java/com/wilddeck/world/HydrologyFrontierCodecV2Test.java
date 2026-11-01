package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyFrontierCodecV2Test {
    private static GlobalHydrologyAddressV2.Sample at(int x) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,2);
    }
    @Test void pendingDischargeSurvivesVersionedRoundTrip() {
        var a=at(1);var b=at(2);var c=at(3);
        var graph=HydrologyFlowGraphV2.solve(List.of(
            new HydrologyFlowGraphV2.Node(a,3,b,false),
            new HydrologyFlowGraphV2.Node(b,2,c,false)));
        var encoded=HydrologyFrontierCodecV2.encode(graph.pendingOutflow());
        assertEquals(graph.pendingOutflow(),HydrologyFrontierCodecV2.decode(encoded));
        assertEquals(encoded,HydrologyFrontierCodecV2.encode(
            HydrologyFrontierCodecV2.decode(encoded)));
        assertTrue(encoded.startsWith(HydrologyFrontierCodecV2.HEADER+"\n"));
    }
    @Test void serializationOrderDoesNotDependOnInputMapOrder() {
        var first=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        first.put(at(4),3.5);first.put(at(1),2.5);
        var second=new LinkedHashMap<GlobalHydrologyAddressV2.Sample,Double>();
        second.put(at(1),2.5);second.put(at(4),3.5);
        assertEquals(HydrologyFrontierCodecV2.encode(first),
                     HydrologyFrontierCodecV2.encode(second));
    }
    @Test void malformedOrDuplicateSnapshotsAreRejected() {
        var header=HydrologyFrontierCodecV2.HEADER+"\n";
        assertThrows(IllegalArgumentException.class,()->
            HydrologyFrontierCodecV2.decode("wrong\n"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyFrontierCodecV2.decode(header+"2,4,1,2,1.0\n2,4,1,2,1.0\n"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyFrontierCodecV2.decode(header+"2,4,1,2,NaN\n"));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyFrontierCodecV2.decode(header+"2,4,1,2,-1\n"));
    }
}
