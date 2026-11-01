package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in terrain binding for sediment-altered lake snapshots. Lake-bed cells
 * must agree exactly with the checkpointed terrain overlay (or seeded terrain
 * if no override exists). Deposited beds and unrelated terrain overrides are
 * then serialized together for the same atomic hydrology checkpoint.
 *
 * The supplied seed and baseline must be independently authoritative.
 * This does not change the global seed, discover new spillways, or authorize
 * topology migration outside the verified lake plan.
 */
public final class HydrologyLakeSedimentTerrainBindingV2 {
    private HydrologyLakeSedimentTerrainBindingV2() {}

    public static String updateWorld(
            String nextWorld,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            HydrologySphericalLakeBasinsV2.Plan before,
            HydrologySphericalLakeBasinsV2.Plan after,
            String previousTerrainWorld) {
        Objects.requireNonNull(nextWorld);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        Objects.requireNonNull(previousTerrainWorld);
        var oldTerrain = HydrologyTerrainOverlayV2.decode(previousTerrainWorld);
        var elevations = HydrologyTerrainOverlayV2.elevations(seed, oldTerrain);
        var original = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologySphericalLakeBasinsV2.Lake>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        var updated = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologySphericalLakeBasinsV2.Lake>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var lake : before.lakes()) {
            if (original.putIfAbsent(lake.anchor(),lake)!=null)
                throw new IllegalArgumentException("Duplicate previous lake");
        }
        for (var lake : after.lakes()) {
            if (updated.putIfAbsent(lake.anchor(),lake)!=null)
                throw new IllegalArgumentException("Duplicate evolved lake");
        }
        if (!original.keySet().equals(updated.keySet()))
            throw new IllegalArgumentException("Lake identity changed during terrain binding");
        var merged = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        merged.putAll(oldTerrain.elevations());
        var seen = new HashSet<GlobalHydrologyAddressV2.Sample>();
        for (var anchor : original.keySet()) {
            var oldLake = original.get(anchor);
            var newLake = updated.get(anchor);
            if (!oldLake.cells().equals(newLake.cells()))
                throw new IllegalArgumentException("Lake cell ownership changed");
            var oldBeds = oldLake.reservoir().bedElevation();
            var newBeds = newLake.reservoir().bedElevation();
            if (oldBeds.length != oldLake.cells().size() ||
                newBeds.length != oldBeds.length)
                throw new IllegalArgumentException("Invalid lake bed geometry");
            for (int i=0;i<oldBeds.length;i++) {
                var address = oldLake.cells().get(i);
                if (!seen.add(address))
                    throw new IllegalArgumentException("Overlapping lake terrain ownership");
                double committed = elevations.at(address);
                if (!Double.isFinite(committed) ||
                    Double.compare(committed,oldBeds[i])!=0)
                    throw new IllegalArgumentException(
                        "Lake bed disagrees with committed terrain");
                if (!Double.isFinite(newBeds[i]) || newBeds[i] < oldBeds[i] ||
                    newBeds[i] > oldLake.reservoir().spillElevation())
                    throw new IllegalArgumentException("Invalid sediment terrain update");
                merged.put(address,newBeds[i]);
            }
        }
        return HydrologyTerrainOverlayV2.encode(
            new HydrologyTerrainOverlayV2.State(nextWorld,merged));
    }
}
