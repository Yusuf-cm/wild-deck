package com.wilddeck.world;

import java.util.*;

/**
 * Optional strict terrain-routing preflight for a riverbed edit.
 *
 * The ordinary mass-balance preview proves conservation and preserves its
 * supplied directed edges. This guard additionally re-solves spherical
 * steepest descent at every changed vertex, every loaded neighbor, and the
 * one-ring unloaded halo. It refuses edits that would silently reroute an
 * existing loaded river or an unloaded neighbor.
 *
 * A priority-flood spill route is not necessarily a steepest-descent route:
 * such cases must use a separately verified drainage migration, not this
 * strict local guard. This is not global lake spillway verification, a
 * distributed transaction, or an authorization to discard in-flight water.
 */
public final class HydrologyRiverbedRouteGuardV2 {
    private HydrologyRiverbedRouteGuardV2() {}

    public record Report(int changedVertices, int checkedLoaded, int checkedUnloaded) {
        public Report {
            if (changedVertices < 0 || checkedLoaded < 0 || checkedUnloaded < 0)
                throw new IllegalArgumentException("Invalid riverbed routing report");
        }
    }

    public static Report verify(
            HydrologySphericalLakeBasinsV2.Plan drainage,
            HydrologyTerrainOverlayV2.State previous,
            HydrologyTerrainOverlayV2.State next,
            HydrologyGlobalSlopeRouterV2.Elevations seed) {
        Objects.requireNonNull(drainage);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(next);
        Objects.requireNonNull(seed);
        HydrologyFlowGraphV2.solve(drainage.routes());
        var nodes = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        var physical = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var node : drainage.routes()) {
            var address = node.address();
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                nodes.putIfAbsent(address, node) != null ||
                !physical.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Nonunique physical riverbed owner");
        }

        var before = HydrologyTerrainOverlayV2.elevations(seed, previous);
        var after = HydrologyTerrainOverlayV2.elevations(seed, next);
        var union = new TreeSet<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        union.addAll(previous.elevations().keySet());
        union.addAll(next.elevations().keySet());
        var changed = new TreeSet<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var address : union) {
            double oldHeight = before.at(address), newHeight = after.at(address);
            if (!Double.isFinite(oldHeight) || !Double.isFinite(newHeight))
                throw new IllegalArgumentException("Nonfinite terrain around riverbed");
            if (Double.compare(oldHeight, newHeight) != 0) changed.add(address);
        }
        if (changed.isEmpty()) return new Report(0, 0, 0);
        if (!drainage.lakes().isEmpty())
            throw new IllegalArgumentException(
                "Lake spillways require separate topology verification");

        var affected = new TreeSet<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var address : changed) {
            var node = nodes.get(address);
            if (node == null || node.terminal() || node.downstream() == null ||
                !nodes.containsKey(node.downstream()))
                throw new IllegalArgumentException(
                    "Riverbed edit has no verified loaded downstream");
            affected.add(address);
            affected.addAll(HydrologySphericalTopologyV2.neighbors(address));
        }
        int loaded = 0, unloaded = 0;
        for (var address : affected) {
            var node = nodes.get(address);
            if (node != null && node.terminal()) {
                // A verified outlet is a physical boundary condition, not a
                // downhill route that may be inferred from a heightfield.
                continue;
            }
            var oldRoute = steepest(address, before);
            var newRoute = steepest(address, after);
            if (!Objects.equals(oldRoute, newRoute))
                throw new IllegalArgumentException(
                    "Riverbed edit changes spherical routing at " + address);
            if (node == null) {
                unloaded++;
            } else {
                if (!Objects.equals(node.downstream(), oldRoute))
                    throw new IllegalArgumentException(
                        "Supplied river route is not verified steepest descent");
                loaded++;
            }
        }
        return new Report(changed.size(), loaded, unloaded);
    }

    private static GlobalHydrologyAddressV2.Sample steepest(
            GlobalHydrologyAddressV2.Sample address,
            HydrologyGlobalSlopeRouterV2.Elevations heights) {
        return HydrologySphericalTopologyV2.route(
            List.of(new HydrologyGlobalSlopeRouterV2.Source(address, 0)),
            heights, Set.of()).get(0).downstream();
    }
}
