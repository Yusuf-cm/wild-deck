package com.wilddeck.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Deterministic geometry identity for a caller-verified spherical lake plan.
 * Captures sorted basin anchors, wet cells, spill targets, outlet identities,
 * and exact IEEE-754 hypsometry. Route topology is bound separately.
 *
 * This is a checkpoint identity, not proof that elevations match real terrain.
 */
public final class HydrologyLakePlanSnapshotIdV2 {
    private HydrologyLakePlanSnapshotIdV2() {}

    public static String of(HydrologySphericalLakeBasinsV2.Plan plan) {
        Objects.requireNonNull(plan);
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            update(digest,"WD-LAKE-GEOMETRY-1\n");
            var lakes=new ArrayList<>(plan.lakes());
            lakes.sort(Comparator.comparing(
                HydrologySphericalLakeBasinsV2.Lake::anchor,
                HydrologyFlowGraphV2.ADDRESS_ORDER));
            update(digest,lakes.size()+"\n");
            var seen=new HashSet<GlobalHydrologyAddressV2.Sample>();
            for(var lake:lakes) {
                if(!seen.add(lake.anchor()))
                    throw new IllegalArgumentException("Duplicate lake anchor");
                var beds=lake.reservoir().bedElevation();
                var areas=lake.reservoir().cellArea();
                if(lake.cells().size()!=beds.length)
                    throw new IllegalArgumentException("Lake geometry size mismatch");
                update(digest,address(lake.anchor())+"|"+address(lake.spillTarget())+
                    "|"+address(lake.confirmedOutlet())+"|"+
                    Double.toHexString(lake.reservoir().spillElevation())+"|"+
                    beds.length+"\n");
                for(int i=0;i<beds.length;i++)
                    update(digest,address(lake.cells().get(i))+"|"+
                        Double.toHexString(beds[i])+"|"+
                        Double.toHexString(areas[i])+"\n");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable",impossible);
        }
    }

    private static String address(GlobalHydrologyAddressV2.Sample sample) {
        Objects.requireNonNull(sample);
        return sample.level()+","+sample.divisions()+","+
            sample.x()+","+sample.y();
    }
    private static void update(MessageDigest digest,String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
