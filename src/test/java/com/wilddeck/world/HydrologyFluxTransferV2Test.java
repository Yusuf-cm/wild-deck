package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyFluxTransferV2Test {
    @Test void receivesBoundaryWaterAndConservesInput(){
        var from=new GlobalHydrologyAddressV2.Sample(2,4,7,5);
        var to=new GlobalHydrologyAddressV2.Sample(2,4,8,5);
        var crossing=new HydrologyBoundaryV2.Crossing(from,to,6);
        double[] incoming=HydrologyFluxTransferV2.incoming(2,4,2,1,5,5,List.of(crossing));
        assertEquals(6,incoming[5]);
        var lakes=LakeSpillV2.fill(5,5,new double[25]);
        var discharge=SpillDischargeV2.accumulate(lakes,new double[25],incoming);
        double total=0;
        int[] next=lakes.spillTo();
        for(int i=0;i<25;i++)if(next[i]<0)total+=discharge.flow()[i];
        assertEquals(6,total,1e-9);
    }
    @Test void aggregatesContributionsAtSharedSample(){
        var from=new GlobalHydrologyAddressV2.Sample(2,4,7,5);
        var to=new GlobalHydrologyAddressV2.Sample(2,4,8,5);
        var crossings=List.of(new HydrologyBoundaryV2.Crossing(from,to,2),
                              new HydrologyBoundaryV2.Crossing(from,to,3));
        assertEquals(5,HydrologyFluxTransferV2.incoming(2,4,2,1,5,5,crossings)[5]);
    }
    @Test void ignoresOtherRegionsAndRejectsResolutionMismatch(){
        var a=new GlobalHydrologyAddressV2.Sample(2,4,7,5);
        var b=new GlobalHydrologyAddressV2.Sample(2,4,8,5);
        var crossing=new HydrologyBoundaryV2.Crossing(a,b,4);
        assertArrayEquals(new double[25],
            HydrologyFluxTransferV2.incoming(2,4,4,1,5,5,List.of(crossing)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyFluxTransferV2.incoming(2,8,2,1,5,5,List.of(crossing)));
    }
}
