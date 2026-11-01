package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only, mass-balanced riverbed incision AND deposition on a verified
 * single-owner routing graph. No terrain or sediment checkpoint is written.
 *
 * The transport result must represent an entirely local closed sediment
 * budget: imports, lake trapping and boundary exports need their own durable
 * coupled transactions. Local erosion lowers its source bed; deposition raises
 * its destination bed. All affected routing edges must remain downhill.
 *
 * This does not discover alternate routes, model bank erosion or guarantee
 * that the supplied drainage is globally authoritative.
 */
public final class HydrologyRiverbedMassBalanceV2 {
    private HydrologyRiverbedMassBalanceV2() {}

    public record Preview(HydrologyTerrainOverlayV2.State terrain,
                          double erodedMass, double depositedMass,
                          int changedVertices) {
        public Preview {
            Objects.requireNonNull(terrain);
            if (!Double.isFinite(erodedMass) || erodedMass < 0 ||
                !Double.isFinite(depositedMass) || depositedMass < 0 ||
                changedVertices < 0)
                throw new IllegalArgumentException("Invalid riverbed mass preview");
        }
    }

    public static Preview plan(
            HydrologySphericalLakeBasinsV2.Plan drainage,
            HydrologyTerrainOverlayV2.State previous,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            HydrologyRiverSedimentTransportV2.Result transport,
            Map<GlobalHydrologyAddressV2.Sample,Double> cellArea,
            double dryBulkDensity, double maximumIncision, double maximumRise) {
        Objects.requireNonNull(drainage);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(eroded);
        Objects.requireNonNull(transport);
        Objects.requireNonNull(cellArea);
        if (!Double.isFinite(dryBulkDensity) || dryBulkDensity <= 0 ||
            !Double.isFinite(maximumIncision) || maximumIncision <= 0 ||
            !Double.isFinite(maximumRise) || maximumRise <= 0)
            throw new IllegalArgumentException("Invalid riverbed physical parameters");

        // Reject cycles, unowned aliases, and fabricated local source budgets.
        HydrologyFlowGraphV2.solve(drainage.routes());
        var nodes = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        var owners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var node : drainage.routes()) {
            Objects.requireNonNull(node);
            var address = node.address();
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                nodes.putIfAbsent(address,node) != null ||
                !owners.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Duplicate geographic riverbed owner");
        }
        if (nodes.isEmpty() || !nodes.keySet().equals(eroded.keySet()) ||
            !nodes.keySet().equals(transport.riverDeposited().keySet()))
            throw new IllegalArgumentException("Incomplete riverbed mass source coverage");

        var lakeCells = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var lake : drainage.lakes())
            for (var cell : lake.cells()) {
                var physical = HydrologyGlobalCatchmentsV2.coordinate(cell);
                if (!owners.contains(physical) || !lakeCells.add(physical))
                    throw new IllegalArgumentException("Invalid lake/riverbed ownership");
            }

        BigDecimal erosion = BigDecimal.ZERO, deposition = BigDecimal.ZERO;
        var original = HydrologyTerrainOverlayV2.elevations(seed,previous);
        var heights = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        heights.putAll(previous.elevations());
        var changed = new HashSet<GlobalHydrologyAddressV2.Sample>();
        for (var node : nodes.values()) {
            var address = node.address();
            double cut = mass(eroded.get(address));
            double fill = mass(transport.riverDeposited().get(address));
            erosion = erosion.add(BigDecimal.valueOf(cut));
            deposition = deposition.add(BigDecimal.valueOf(fill));
            if (cut == 0 && fill == 0) continue;
            if (lakeCells.contains(HydrologyGlobalCatchmentsV2.coordinate(address)) ||
                node.terminal() || node.downstream() == null ||
                !nodes.containsKey(node.downstream()))
                throw new IllegalArgumentException("Riverbed edit needs loaded nonlake downstream");
            var area = cellArea.get(address);
            if (area == null || !Double.isFinite(area) || area <= 0)
                throw new IllegalArgumentException("Invalid riverbed cell area");
            double incision = cut / (area * dryBulkDensity);
            double rise = fill / (area * dryBulkDensity);
            if (!Double.isFinite(incision) || !Double.isFinite(rise) ||
                incision > maximumIncision || rise > maximumRise)
                throw new IllegalArgumentException("Riverbed change exceeds physical limit");
            double before = original.at(address);
            double after = before + (rise - incision);
            if (!Double.isFinite(before) || !Double.isFinite(after) ||
                (Double.compare(after,before) == 0 && Double.compare(cut,fill) != 0))
                throw new IllegalArgumentException("Riverbed edit lost in elevation precision");
            if (Double.compare(after,before) != 0) {
                heights.put(address,after);
                changed.add(address);
            }
        }
        var budget = transport.budget();
        if (!Double.isFinite(budget.eroded()) ||
            !Double.isFinite(budget.riverDeposited()) ||
            budget.pendingExported() != 0 || budget.terminalExported() != 0 ||
            budget.lakeCaptured() != 0 || budget.residual() != 0 ||
            erosion.compareTo(BigDecimal.valueOf(budget.eroded())) != 0 ||
            deposition.compareTo(BigDecimal.valueOf(budget.riverDeposited())) != 0 ||
            erosion.compareTo(deposition) != 0)
            throw new IllegalArgumentException("Riverbed requires a closed local mass budget");

        var next = new HydrologyTerrainOverlayV2.State(previous.world(),heights);
        var updated = HydrologyTerrainOverlayV2.elevations(seed,next);
        for (var node : nodes.values()) {
            var down = node.downstream();
            if (down == null || (!changed.contains(node.address()) &&
                                 !changed.contains(down))) continue;
            if (!nodes.containsKey(down) ||
                lakeCells.contains(HydrologyGlobalCatchmentsV2.coordinate(node.address())) ||
                lakeCells.contains(HydrologyGlobalCatchmentsV2.coordinate(down)))
                throw new IllegalArgumentException("Riverbed edit touches unresolved route");
            double oldUp = original.at(node.address()), oldDown = original.at(down);
            double newUp = updated.at(node.address()), newDown = updated.at(down);
            if (!Double.isFinite(oldUp) || !Double.isFinite(oldDown) ||
                !(oldUp > oldDown) || !(newUp > newDown))
                throw new IllegalArgumentException("Riverbed edit reverses drainage");
        }
        return new Preview(next,erosion.doubleValue(),deposition.doubleValue(),
                           changed.size());
    }

    private static double mass(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid riverbed sediment mass");
        return value;
    }
}
