package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverNetworkV2Test {
    @Test void channelCellsShareStableOutlet(){
        var drainage=RegionalDrainageV2.route(4,1,new double[]{4,3,2,1},
                                              new double[]{1,1,1,1});
        var network=RiverNetworkV2.extract(drainage,2);
        assertEquals(2,network.channels().size());
        assertEquals(3,network.channels().get(0).outlet());
        assertEquals(3,network.channels().get(1).outlet());
        assertEquals(2,network.channels().get(0).flow());
        assertEquals(3,network.channels().get(1).flow());
    }
    @Test void differentBasinsHaveDifferentOutlets(){
        var drainage=RegionalDrainageV2.route(5,1,new double[]{1,3,5,3,1},
                                              new double[]{1,1,1,1,1});
        var network=RiverNetworkV2.extract(drainage,1);
        assertEquals(2,network.channels().size());
        assertEquals(0,network.channels().get(0).outlet());
        assertEquals(4,network.channels().get(1).outlet());
    }
    @Test void thresholdFiltersChannels(){
        var drainage=RegionalDrainageV2.route(3,1,new double[]{3,2,1},
                                              new double[]{1,1,1});
        assertEquals(0,RiverNetworkV2.extract(drainage,5).channels().size());
        assertThrows(IllegalArgumentException.class,()->RiverNetworkV2.extract(drainage,0));
    }
    @Test void generatedMosaicProducesRepeatableChannels(){
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);
        var climate=new PlanetClimateV2(world);
        var mosaic=ConnectedDrainageV2.generate(world,climate,4,5,4,2,2,8);
        var a=RiverNetworkV2.extract(mosaic.drainage(),2);
        var b=RiverNetworkV2.extract(mosaic.drainage(),2);
        assertEquals(a,b);
    }
}
