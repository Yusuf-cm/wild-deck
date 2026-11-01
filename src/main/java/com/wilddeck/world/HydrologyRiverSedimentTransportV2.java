package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Deterministic, single-epoch dry-sediment mass transport over verified,
 * uniquely owned global river vertices. Each river vertex contributes its
 * local eroded mass once. A caller-supplied transport capacity retains the
 * excess as river-bed deposition. Sediment entering a lake cell is captured
 * by that lake exactly once and is not also sent along the river edge.
 *
 * Lake capture is an idealized 100% trapping assumption, not a settling model.
 * Terminal exports and unloaded-frontier exports remain explicitly accounted
 * for. This does not persist river deposits or invent routes through unknown
 * terrain; a later terrain/topology update must be independently verified.
 */
public final class HydrologyRiverSedimentTransportV2 {
    private HydrologyRiverSedimentTransportV2() {}

    public record Budget(double eroded, double riverDeposited,
                         double lakeCaptured, double terminalExported,
                         double pendingExported, double residual) {}

    public record Result(
            Map<GlobalHydrologyAddressV2.Sample,Double> riverDeposited,
            Map<GlobalHydrologyAddressV2.Sample,Double> lakeCaptured,
            Map<GlobalHydrologyAddressV2.Sample,Double> transported,
            Map<GlobalHydrologyAddressV2.Sample,Double> terminalExport,
            Map<GlobalHydrologyAddressV2.Sample,Double> pendingExport,
            Budget budget) {
        public Result {
            riverDeposited = sorted(riverDeposited);
            lakeCaptured = sorted(lakeCaptured);
            transported = sorted(transported);
            terminalExport = sorted(terminalExport);
            pendingExport = sorted(pendingExport);
            Objects.requireNonNull(budget);
        }
    }

    /**
     * Local erosion only. Imported sediment is deliberately absent from this
     * legacy entry point so existing single-region budgets are unchanged.
     */
    public static Result route(HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity) {
        return routeInternal(plan,eroded,transportCapacity,Map.of());
    }

    /**
     * Route externally delivered sediment at owned receiving vertices.
     * The caller must deduplicate/authenticate receipt identities before
     * passing this map: mass totals alone cannot prevent replay.
     * The returned source budget includes local erosion AND imported mass.
     */
    public static Imported routeWithIncoming(
            HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            Map<GlobalHydrologyAddressV2.Sample,Double> imported) {
        var result=routeInternal(plan,eroded,transportCapacity,imported);
        BigDecimal local=BigDecimal.ZERO, external=BigDecimal.ZERO;
        for (var value:eroded.values()) local=local.add(mass(value));
        for (var value:imported.values()) external=external.add(mass(value));
        return new Imported(result,finite(local),finite(external));
    }

    public record Imported(Result result,double localEroded,double imported) {
        public Imported {
            Objects.requireNonNull(result);
            if (!Double.isFinite(localEroded) || localEroded < 0 ||
                !Double.isFinite(imported) || imported < 0)
                throw new IllegalArgumentException("Invalid imported sediment budget");
        }
    }

    private static Result routeInternal(HydrologySphericalLakeBasinsV2.Plan plan,
            Map<GlobalHydrologyAddressV2.Sample,Double> eroded,
            Map<GlobalHydrologyAddressV2.Sample,Double> transportCapacity,
            Map<GlobalHydrologyAddressV2.Sample,Double> external) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(eroded);
        Objects.requireNonNull(transportCapacity);
        Objects.requireNonNull(external);
        // This validates global ownership, single resolution, adjacent edges,
        // and acyclicity even when the caller has only loaded a finite window.
        HydrologyFlowGraphV2.solve(plan.routes());
        var nodes = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : plan.routes()) {
            Objects.requireNonNull(node);
            if (nodes.putIfAbsent(node.address(),node) != null)
                throw new IllegalArgumentException("Duplicate sediment source vertex");
        }
        if (!nodes.keySet().equals(eroded.keySet()) ||
            !nodes.keySet().equals(transportCapacity.keySet()))
            throw new IllegalArgumentException("Sediment inputs must cover each source exactly once");
        var membership = new HashMap<GlobalHydrologyAddressV2.Sample,
            GlobalHydrologyAddressV2.Sample>();
        var captured = new TreeMap<GlobalHydrologyAddressV2.Sample,BigDecimal>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var lake : plan.lakes()) {
            Objects.requireNonNull(lake);
            if (captured.putIfAbsent(lake.anchor(),BigDecimal.ZERO) != null)
                throw new IllegalArgumentException("Duplicate lake sediment identity");
            for (var cell : lake.cells()) {
                var node = nodes.get(cell);
                if (node == null || node.terminal() || node.downstream() == null ||
                    membership.putIfAbsent(cell,lake.anchor()) != null)
                    throw new IllegalArgumentException("Invalid or overlapping lake sediment ownership");
            }
        }
        var indegree = new HashMap<GlobalHydrologyAddressV2.Sample,Integer>();
        var incoming = new HashMap<GlobalHydrologyAddressV2.Sample,BigDecimal>();
        BigDecimal total = BigDecimal.ZERO;
        for (var node : nodes.values()) {
            indegree.put(node.address(),0);
            BigDecimal mass = mass(eroded.get(node.address()));
            mass(transportCapacity.get(node.address()));
            total = total.add(mass);
        }
        // Imports are already counted by the sender's separate epoch budget.
        // Within this receiving solve they enter once at the addressed node.
        for (var entry : external.entrySet()) {
            if (!nodes.containsKey(Objects.requireNonNull(entry.getKey())))
                throw new IllegalArgumentException("Unknown sediment receiving vertex");
            total = total.add(mass(entry.getValue()));
        }
        for (var node : nodes.values())
            if (node.downstream() != null && nodes.containsKey(node.downstream()))
                indegree.merge(node.downstream(),1,Integer::sum);
        var ready = new PriorityQueue<GlobalHydrologyAddressV2.Sample>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : indegree.entrySet())
            if (entry.getValue() == 0) ready.add(entry.getKey());
        var riverDeposited = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var transported = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var terminal = new TreeMap<GlobalHydrologyAddressV2.Sample,BigDecimal>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var pending = new TreeMap<GlobalHydrologyAddressV2.Sample,BigDecimal>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        BigDecimal riverTotal = BigDecimal.ZERO;
        int visited = 0;
        while (!ready.isEmpty()) {
            var address = ready.remove();
            visited++;
            var node = nodes.get(address);
            BigDecimal available = incoming.getOrDefault(address,BigDecimal.ZERO)
                .add(mass(eroded.get(address)))
                .add(external.containsKey(address)
                    ? mass(external.get(address)) : BigDecimal.ZERO);
            BigDecimal passing;
            var lake = membership.get(address);
            if (lake != null) {
                captured.merge(lake,available,BigDecimal::add);
                passing = BigDecimal.ZERO;
                riverDeposited.put(address,0.0);
            } else {
                passing = available.min(mass(transportCapacity.get(address)));
                BigDecimal deposit = available.subtract(passing);
                riverTotal = riverTotal.add(deposit);
                riverDeposited.put(address,finite(deposit));
            }
            transported.put(address,finite(passing));
            if (lake == null) {
                if (node.terminal()) terminal.merge(address,passing,BigDecimal::add);
                else if (node.downstream() == null) pending.merge(address,passing,BigDecimal::add);
                else if (!nodes.containsKey(node.downstream()))
                    pending.merge(node.downstream(),passing,BigDecimal::add);
                else incoming.merge(node.downstream(),passing,BigDecimal::add);
            }
            if (node.downstream() != null && nodes.containsKey(node.downstream()) &&
                indegree.merge(node.downstream(),-1,Integer::sum) == 0)
                ready.add(node.downstream());
        }
        if (visited != nodes.size())
            throw new IllegalArgumentException("Cyclic sediment routing");
        BigDecimal lakeTotal = sum(captured), terminalTotal = sum(terminal),
            pendingTotal = sum(pending);
        BigDecimal residual = total.subtract(riverTotal).subtract(lakeTotal)
            .subtract(terminalTotal).subtract(pendingTotal);
        if (residual.signum() != 0)
            throw new IllegalArgumentException("Sediment mass conservation failure");
        return new Result(riverDeposited,toDouble(captured),transported,
            toDouble(terminal),toDouble(pending),
            new Budget(finite(total),finite(riverTotal),finite(lakeTotal),
                finite(terminalTotal),finite(pendingTotal),finite(residual)));
    }

    private static BigDecimal mass(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid sediment mass or capacity");
        return BigDecimal.valueOf(value);
    }
    private static double finite(BigDecimal value) {
        double result = value.doubleValue();
        if (!Double.isFinite(result))
            throw new IllegalArgumentException("Sediment mass overflow");
        return result;
    }
    private static BigDecimal sum(Map<?,BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (var value : values.values()) total = total.add(value);
        return total;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> toDouble(
            Map<GlobalHydrologyAddressV2.Sample,BigDecimal> values) {
        var result = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var entry : values.entrySet()) result.put(entry.getKey(),finite(entry.getValue()));
        return result;
    }
    private static Map<GlobalHydrologyAddressV2.Sample,Double> sorted(
            Map<GlobalHydrologyAddressV2.Sample,Double> values) {
        var result = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        result.putAll(values);
        return Collections.unmodifiableMap(result);
    }
}
