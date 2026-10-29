package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectedHydrologyV2Test {
    @Test void connectedMosaicConservesRainfallAndIsRepeatable(){
        var world=new PlanetSurfaceV2(Planet.emptyEarthSized(),12345L);
        var climate=new PlanetClimateV2(world);
        var a=ConnectedHydrologyV2.generate(world,climate,2,1,1,2,2,4,0.1);
        var b=ConnectedHydrologyV2.generate(world,climate,2,1,1,2,2,4,0.1);
        assertEquals(a.internalTransfers(),b.internalTransfers());
        var h=a.hydrology();
        double[] rain=h.terrain().rainfall(),flow=h.spillDischarge().flow();
        int[] next=h.lakes().spillTo();
        double total=0,exits=0;
        for(int i=0;i<rain.length;i++){
            total+=rain[i];
            if(next[i]<0)exits+=flow[i];
        }
        assertEquals(total,exits,1e-6);
    }
}
