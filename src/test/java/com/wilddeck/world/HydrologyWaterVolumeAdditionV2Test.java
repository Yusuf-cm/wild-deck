package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyWaterVolumeAdditionV2Test {
    private static final GlobalHydrologyAddressV2.Sample M =
        new GlobalHydrologyAddressV2.Sample(1,2,5,2);

    @Test void ordinaryWaterAdditionsAndZeroRemainRepresentable() {
        assertEquals(5.0,HydrologyWaterVolumeAdditionV2.add(2,3),0);
        assertEquals(1e16+128,HydrologyWaterVolumeAdditionV2.add(1e16,128),0);
        assertEquals(1e16,HydrologyWaterVolumeAdditionV2.add(1e16,0),0);
        assertEquals(0,HydrologyWaterVolumeAdditionV2.add(0,0),0);
    }

    @Test void tinyNewReceiptCannotDisappearInsideHugeFrontier() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyWaterVolumeAdditionV2.add(1e16,1));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.combined(
                Map.of(M,1e16),Map.of(M,1.0)));
    }

    @Test void existingFrontierCannotDisappearInsideHugeNewReceipt() {
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyWaterVolumeAdditionV2.add(1,1e16));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalLakeInboxCommitV2.combined(
                Map.of(M,1.0),Map.of(M,1e16)));
    }

    @Test void zeroReceiptsAndIndependentDestinationsAreStillValid() {
        var other=new GlobalHydrologyAddressV2.Sample(1,2,3,2);
        assertEquals(Map.of(M,1e16,other,1.0),
            HydrologySeasonalLakeInboxCommitV2.combined(
                Map.of(M,1e16),Map.of(M,0.0,other,1.0)));
        assertEquals(Map.of(M,1e16+128),
            HydrologySeasonalLakeInboxCommitV2.combined(
                Map.of(M,1e16),Map.of(M,128.0)));
    }

    @Test void invalidVolumesAndOverflowAreRejected() {
        for (double invalid : new double[] {-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,() ->
                HydrologyWaterVolumeAdditionV2.add(invalid,1));
            assertThrows(IllegalArgumentException.class,() ->
                HydrologyWaterVolumeAdditionV2.add(1,invalid));
        }
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyWaterVolumeAdditionV2.add(Double.MAX_VALUE,Double.MAX_VALUE));
    }
}
