package com.wilddeck.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Deterministic content identity for a verified, complete spherical drainage
 * topology. Ignores seasonal localWater, but binds canonical source ownership,
 * directed edges, terminal flags and resolved outlet catchments.
 *
 * This is a snapshot identifier, NOT proof of physical outlet correctness,
 * lake-volume reconciliation or permission to activate a new topology.
 */
public final class HydrologyDrainageSnapshotIdV2 {
    private HydrologyDrainageSnapshotIdV2() {}

    public static String of(HydrologySphericalRouteAtlasV2 atlas) {
        Objects.requireNonNull(atlas);
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var routes=new ArrayList<>(atlas.routes());
            routes.sort(Comparator.comparing(HydrologyFlowGraphV2.Node::address,
                HydrologyFlowGraphV2.ADDRESS_ORDER));
            update(digest,"WD-DRAINAGE-TOPOLOGY-1\n");
            update(digest,Integer.toString(routes.size())+"\n");
            for(var node:routes) {
                var catchment=atlas.catchments().get(node.address());
                if(catchment==null)
                    throw new IllegalArgumentException("Unresolved atlas catchment");
                update(digest,address(node.address())+"|"+
                    (node.terminal()?"T":"R")+"|"+
                    (node.downstream()==null?"-":address(node.downstream()))+"|"+
                    address(catchment.outlet())+"\n");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable",impossible);
        }
    }

    private static void update(MessageDigest digest,String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String address(GlobalHydrologyAddressV2.Sample sample) {
        return sample.level()+","+sample.divisions()+","+
            sample.x()+","+sample.y();
    }
}
