package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only, conservative river-bed deposition preview on a verified,
 * single-owner spherical drainage graph. It never changes a checkpoint.
 *
 * Deposits are converted from dry mass to bed rise using caller-supplied
 * cell area and dry bulk density. Only non-lake, nonterminal vertices with
 * a loaded downstream may change. Every affected non-lake directed edge must
 * remain strictly downhill, including edges from upstream neighbors.
 *
 * This is deliberately NOT a new authoritative routing solve: competing
 * downhill neighbors, off-graph terrain, spillway migration and exported
 * sediment must be reverified before a caller can commit the preview.
 */
public final class HydrologyRiverbedTerrainPreviewV2 {
    private HydrologyRiverbedTerrainPreviewV2() {}

    public record Preview(HydrologyTerrainOverlayV2.State terrain,
                          double depositedMass, int changedVertices) {
        public Preview {
            Objects.requireNonNull(terrain);
            if (!Double.isFinite(depositedMass) || depositedMass < 0 ||
                changedVertices < 0)
                throw new IllegalArgumentException("Invalid riverbed preview");
        }
    }

    /**
     * Bridge the actual conservative river transport result to terrain.
     * Until lake infill and exported sediment have an atomic companion
     * transaction, accept only entirely local riverbed deposition. This
     * prevents a caller from persisting a partial mass budget as terrain.
     */
    public static Preview planFromTransport(
            HydrologySphericalLakeBasinsV2.Plan drainage,
            HydrologyTerrainOverlayV2.State previous,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            HydrologyRiverSedimentTransportV2.Result sediment,
            Map<GlobalHydrologyAddressV2.Sample,Double> cellArea,
            double dryBulkDensity, double maximumRise) {
        Objects.requireNonNull(sediment);
        var budget = sediment.budget();
        if (!Double.isFinite(budget.eroded()) ||
            !Double.isFinite(budget.riverDeposited()) ||
            budget.eroded() < 0 || budget.riverDeposited() < 0 ||
            budget.pendingExported() != 0 || budget.terminalExported() != 0 ||
            budget.lakeCaptured() != 0 || budget.residual() != 0)
            throw new IllegalArgumentException(
                "Riverbed preview requires all sediment retained locally");
        var preview = plan(drainage,previous,seed,sediment.riverDeposited(),
            cellArea,dryBulkDensity,maximumRise);
        double tolerance = Math.max(1e-8,Math.abs(budget.riverDeposited())*1e-10);
        if (Math.abs(preview.depositedMass()-budget.riverDeposited()) > tolerance ||
            Math.abs(budget.eroded()-budget.riverDeposited()) > tolerance)
            throw new IllegalArgumentException("Riverbed transport budget mismatch");
        return preview;
    }

    public static Preview plan(
            HydrologySphericalLakeBasinsV2.Plan drainage,
            HydrologyTerrainOverlayV2.State previous,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,Double> depositedMass,
            Map<GlobalHydrologyAddressV2.Sample,Double> cellArea,
            double dryBulkDensity, double maximumRise) {
        Objects.requireNonNull(drainage);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(depositedMass);
        Objects.requireNonNull(cellArea);
        if (!Double.isFinite(dryBulkDensity) || dryBulkDensity <= 0 ||
            !Double.isFinite(maximumRise) || maximumRise <= 0)
            throw new IllegalArgumentException("Invalid riverbed geometry or rise limit");

        var nodes = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        var owners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var node : drainage.routes()) {
            Objects.requireNonNull(node);
            var address = node.address();
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                nodes.putIfAbsent(address,node) != null ||
                !owners.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Duplicate or noncanonical river vertex");
        }
        var lakes = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var lake : drainage.lakes())
            for (var cell : lake.cells()) {
                var physical = HydrologyGlobalCatchmentsV2.coordinate(cell);
                if (!owners.contains(physical) || !lakes.add(physical))
                    throw new IllegalArgumentException("Invalid lake riverbed ownership");
            }

        var elevations = HydrologyTerrainOverlayV2.elevations(seed,previous);
        var merged = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        merged.putAll(previous.elevations());
        var changed = new HashSet<GlobalHydrologyAddressV2.Sample>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : depositedMass.entrySet()) {
            var address = Objects.requireNonNull(entry.getKey());
            var mass = Objects.requireNonNull(entry.getValue());
            if (!Double.isFinite(mass) || mass < 0 || !nodes.containsKey(address))
                throw new IllegalArgumentException("Invalid riverbed sediment mass");
            total = total.add(BigDecimal.valueOf(mass));
            if (mass == 0) continue;
            var node = nodes.get(address);
            if (lakes.contains(HydrologyGlobalCatchmentsV2.coordinate(address)) ||
                node.terminal() || node.downstream() == null ||
                !nodes.containsKey(node.downstream()))
                throw new IllegalArgumentException("Unverified riverbed deposition route");
            var area = cellArea.get(address);
            if (area == null || !Double.isFinite(area) || area <= 0)
                throw new IllegalArgumentException("Invalid riverbed cell area");
            double rise = mass / (area * dryBulkDensity);
            double oldHeight = elevations.at(address);
            double newHeight = oldHeight + rise;
            if (!Double.isFinite(rise) || rise <= 0 || rise > maximumRise ||
                !Double.isFinite(oldHeight) || !Double.isFinite(newHeight) ||
                newHeight <= oldHeight)
                throw new IllegalArgumentException("Invalid riverbed height increment");
            merged.put(address,newHeight);
            changed.add(address);
        }
        if (!Double.isFinite(total.doubleValue()))
            throw new IllegalArgumentException("Riverbed mass budget overflow");

        // Validate BOTH sides of every touched edge. Raising a downstream
        // bed can invalidate an upstream river even if its own bed is unchanged.
        var next = HydrologyTerrainOverlayV2.elevations(seed,
            new HydrologyTerrainOverlayV2.State(previous.world(),merged));
        for (var node : nodes.values()) {
            var downstream = node.downstream();
            if (downstream == null ||
                (!changed.contains(node.address()) && !changed.contains(downstream)))
                continue;
            if (!nodes.containsKey(downstream))
                throw new IllegalArgumentException("Changed river touches unloaded route");
            if (lakes.contains(HydrologyGlobalCatchmentsV2.coordinate(node.address())) ||
                lakes.contains(HydrologyGlobalCatchmentsV2.coordinate(downstream)))
                continue;
            double oldUp = elevations.at(node.address());
            double oldDown = elevations.at(downstream);
            double newUp = next.at(node.address());
            double newDown = next.at(downstream);
            if (!Double.isFinite(oldUp) || !Double.isFinite(oldDown) ||
                !(oldUp > oldDown) || !(newUp > newDown))
                throw new IllegalArgumentException("Riverbed deposition reverses drainage");
        }
        return new Preview(new HydrologyTerrainOverlayV2.State(previous.world(),merged),
            total.doubleValue(),changed.size());
    }
}
