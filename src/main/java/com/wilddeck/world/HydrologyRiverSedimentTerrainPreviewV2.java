package com.wilddeck.world;

import java.util.*;

/**
 * Opt-in candidate terrain update from already verified river sediment routes.
 * Dry mass is converted to vertical bed change using caller-supplied dry bulk
 * density and spherical cell areas. Lake infill is deliberately excluded:
 * HydrologyLakeSedimentNetworkV2 owns those beds separately.
 *
 * This checks existing known river edges, NOT alternative D8 neighbors.
 * The result is a preview and MUST be independently reverified for drainage
 * before it can become authoritative or be committed to a world checkpoint.
 * No unknown downstream or terminal outlet may be altered.
 */
public final class HydrologyRiverSedimentTerrainPreviewV2 {
    private HydrologyRiverSedimentTerrainPreviewV2() {}

    public record Preview(HydrologyTerrainOverlayV2.State candidate,
                          HydrologyRiverSedimentTransportV2.Result sediment,
                          double erodedMass, double depositedMass) {
        public Preview {
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(sediment);
            if (!Double.isFinite(erodedMass) || erodedMass < 0 ||
                !Double.isFinite(depositedMass) || depositedMass < 0)
                throw new IllegalArgumentException("Invalid terrain mass budget");
        }
    }

    public static Preview preview(
            HydrologyTerrainOverlayV2.State previous,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            Map<GlobalHydrologyAddressV2.Sample,Double> cellArea,
            double dryBulkDensity) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(eroded);
        Objects.requireNonNull(transportCapacity);
        Objects.requireNonNull(cellArea);
        if (!Double.isFinite(dryBulkDensity) || dryBulkDensity <= 0)
            throw new IllegalArgumentException("Invalid river sediment density");

        var routed=HydrologyRiverSedimentTransportV2.route(
            plan,eroded,transportCapacity);
        if (!routed.pendingExport().isEmpty())
            throw new IllegalArgumentException(
                "Unloaded sediment frontier requires a durable transfer");
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node:plan.routes()) {
            if (nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException("Duplicate terrain source");
        }
        if (!nodes.keySet().equals(cellArea.keySet()))
            throw new IllegalArgumentException("Missing or extra spherical cell areas");

        var lakeCells=new HashSet<GlobalHydrologyAddressV2.Sample>();
        for (var lake:plan.lakes())
            for (var cell:lake.cells())
                if (!lakeCells.add(cell))
                    throw new IllegalArgumentException("Overlapping lake terrain");
        var heights=HydrologyTerrainOverlayV2.elevations(seed,previous);
        var changed=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        changed.putAll(previous.elevations());
        double removed=0, deposited=0;
        for (var node:nodes.values()) {
            var address=node.address();
            double area=Objects.requireNonNull(cellArea.get(address));
            if (!Double.isFinite(area) || area<=0)
                throw new IllegalArgumentException("Invalid spherical cell area");
            double cut=Objects.requireNonNull(eroded.get(address));
            double fill=Objects.requireNonNull(routed.riverDeposited().get(address));
            if (lakeCells.contains(address)) {
                if (cut!=0 || fill!=0)
                    throw new IllegalArgumentException(
                        "Lake bed changes belong to the lake sediment model");
                continue;
            }
            // Gross erosion and deposition remain real mass transfers even
            // when they cancel to zero net elevation change in this cell.
            removed+=cut;
            deposited+=fill;
            double netMass=fill-cut;
            if (netMass==0) continue;
            if (node.terminal() || node.downstream()==null ||
                !nodes.containsKey(node.downstream()))
                throw new IllegalArgumentException(
                    "Cannot alter unverified frontier or terminal terrain");
            double old=heights.at(address);
            double delta=netMass/dryBulkDensity/area;
            double next=old+delta;
            if (!Double.isFinite(old) || !Double.isFinite(delta) ||
                !Double.isFinite(next) || Double.compare(next,old)==0)
                throw new IllegalArgumentException("Unrepresentable terrain change");
            changed.put(address,next);
        }
        if (!Double.isFinite(removed) || !Double.isFinite(deposited))
            throw new IllegalArgumentException("River sediment terrain mass overflow");

        // Verify every affected known non-lake edge remains strictly downhill.
        // Full neighbor rerouting is a separate mandatory acceptance gate.
        for (var node:nodes.values()) {
            var target=node.downstream();
            if (target==null || !nodes.containsKey(target) ||
                lakeCells.contains(node.address()) || lakeCells.contains(target))
                continue;
            double beforeA=heights.at(node.address());
            double beforeB=heights.at(target);
            double afterA=changed.getOrDefault(node.address(),beforeA);
            double afterB=changed.getOrDefault(target,beforeB);
            if (!Double.isFinite(beforeA) || !Double.isFinite(beforeB) ||
                !Double.isFinite(afterA) || !Double.isFinite(afterB))
                throw new IllegalArgumentException("Invalid river edge elevations");
            if ((Double.compare(beforeA,afterA)!=0 ||
                 Double.compare(beforeB,afterB)!=0) &&
                (!(beforeA>beforeB) || !(afterA>afterB)))
                throw new IllegalArgumentException(
                    "River sediment requires a verified drainage transition");
        }
        return new Preview(new HydrologyTerrainOverlayV2.State(
            previous.world(),changed),routed,removed,deposited);
    }
}
