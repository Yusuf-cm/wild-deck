package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakePlanSnapshotIdV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(1,2,x,y);
    }
    private static HydrologySphericalLakeBasinsV2.Lake lake(
            GlobalHydrologyAddressV2.Sample anchor,double bed) {
        return new HydrologySphericalLakeBasinsV2.Lake(
            anchor,List.of(anchor),at(0,2),at(0,2),
            new LakeReservoirV2.Basin(new double[]{bed},
                new double[]{2},10));
    }

    @Test void lakeOrderDoesNotChangeIdentityButGeometryDoes() {
        var a=lake(at(3,2),0);
        var b=lake(at(4,2),-5);
        var forward=new HydrologySphericalLakeBasinsV2.Plan(
            List.of(),List.of(a,b));
        var reverse=new HydrologySphericalLakeBasinsV2.Plan(
            List.of(),List.of(b,a));
        var modified=new HydrologySphericalLakeBasinsV2.Plan(
            List.of(),List.of(a,lake(at(4,2),-4)));
        assertEquals(HydrologyLakePlanSnapshotIdV2.of(forward),
            HydrologyLakePlanSnapshotIdV2.of(reverse));
        assertNotEquals(HydrologyLakePlanSnapshotIdV2.of(forward),
            HydrologyLakePlanSnapshotIdV2.of(modified));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLakePlanSnapshotIdV2.of(
                new HydrologySphericalLakeBasinsV2.Plan(
                    List.of(),List.of(a,a))));
    }

    @Test void legacyAndWetBoundCodecsRoundTripWithoutDroppingGeometry() {
        var topology="a".repeat(64);
        var geometry="b".repeat(64);
        var lake=HydrologyLakeStateV2.encode(
            new HydrologyLakeStateV2.State("world",Map.of(at(3,2),5.0)));
        var legacy=new HydrologyDrainageActivationV2.BoundState(topology,lake);
        var wet=new HydrologyDrainageActivationV2.BoundState(topology,lake,geometry);
        var legacyPayload=HydrologyDrainageActivationV2.encode(legacy);
        var wetPayload=HydrologyDrainageActivationV2.encode(wet);
        assertEquals(legacy,HydrologyDrainageActivationV2.decode(legacyPayload));
        assertEquals(wet,HydrologyDrainageActivationV2.decode(wetPayload));
        assertNotEquals(legacyPayload,wetPayload);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyDrainageActivationV2.decode(wetPayload.replace(geometry,
                "c".repeat(63))));
    }
}
