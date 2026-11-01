package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyBoundaryV2Test {
    @Test void exportsInteriorToBoundaryCrossingWithGlobalCoordinates(){
        var network=new RiverNetworkV2.Network(3,3,List.of(
            new RiverNetworkV2.RiverCell(4,5,5,8)));
        var result=HydrologyBoundaryV2.arrivingAtPerimeter(network,2,4,1,1);
        assertEquals(1,result.size());
        var crossing=result.get(0);
        assertEquals(new GlobalHydrologyAddressV2.Sample(2,4,5,5),crossing.from());
        assertEquals(new GlobalHydrologyAddressV2.Sample(2,4,6,5),crossing.to());
        assertEquals(8,crossing.flow());
    }
    @Test void excludesInteriorLinksAndPerimeterToPerimeterLinks(){
        var network=new RiverNetworkV2.Network(5,5,List.of(
            new RiverNetworkV2.RiverCell(12,13,13,5),
            new RiverNetworkV2.RiverCell(0,1,1,7)));
        assertTrue(HydrologyBoundaryV2.arrivingAtPerimeter(network,2,4,1,1).isEmpty());
    }
    @Test void rejectsInvalidFlow(){
        assertThrows(IllegalArgumentException.class,()->
            new HydrologyBoundaryV2.Crossing(
                new GlobalHydrologyAddressV2.Sample(0,4,0,0),
                new GlobalHydrologyAddressV2.Sample(0,4,1,0),Double.NaN));
    }
}
