package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GlobalHydrologyAddressV2Test {
    @Test void sameSharedVertexAcrossMosaicsHasSameAddress(){
        // At level 2, region 1's east edge is region 2's west edge.
        var left=GlobalHydrologyAddressV2.at(2,4,1,0,5,5,2*5+4);
        var right=GlobalHydrologyAddressV2.at(2,4,2,0,5,5,2*5);
        assertEquals(left,right);
    }
    @Test void mosaicExtentDoesNotAffectSampleIdentity(){
        var small=GlobalHydrologyAddressV2.at(2,4,1,1,5,5,3*5+2);
        var large=GlobalHydrologyAddressV2.at(2,4,1,1,9,9,3*9+2);
        assertEquals(small,large);
    }
    @Test void channelEndpointsHaveStableAddresses(){
        var network=new RiverNetworkV2.Network(3,3,
            List.of(new RiverNetworkV2.RiverCell(4,5,5,7)));
        var channel=GlobalHydrologyAddressV2.channels(network,2,4,1,1).get(0);
        assertEquals(new GlobalHydrologyAddressV2.Sample(2,4,5,5),channel.sample());
        assertEquals(new GlobalHydrologyAddressV2.Sample(2,4,6,5),channel.downstream());
        assertEquals(7,channel.flow());
    }
    @Test void rejectsOutOfBoundsCoordinates(){
        assertThrows(IllegalArgumentException.class,
            ()->GlobalHydrologyAddressV2.at(0,4,0,0,5,5,-1));
        assertThrows(IllegalArgumentException.class,
            ()->new GlobalHydrologyAddressV2.Sample(0,4,9,0));
    }
}
