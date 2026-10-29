package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Independent finite-volume audit for a solved, uniquely owned flow graph.
 * Every node contributes its local water once; every unit must leave through
 * either a verified outlet or an unresolved frontier. Internal discharge is
 * intentionally excluded because counting it again double-counts confluences.
 *
 * This is a per-epoch routing audit, not a global water-cycle balance. Parked
 * receipts and lake storage must be accounted for separately across epochs.
 */
public final class HydrologyConservationV2 {
    private HydrologyConservationV2() {}

    public record Budget(double input,double outlet,double frontier,double residual) {
        public Budget {
            if(!Double.isFinite(input)||!Double.isFinite(outlet)||
               !Double.isFinite(frontier)||!Double.isFinite(residual))
                throw new IllegalArgumentException("Nonfinite water budget");
        }
    }

    public static Budget verify(Collection<HydrologyFlowGraphV2.Node> nodes,
                                HydrologyFlowGraphV2.Result solved) {
        Objects.requireNonNull(nodes);
        Objects.requireNonNull(solved);
        var seen=new HashSet<GlobalHydrologyAddressV2.Sample>();
        BigDecimal input=BigDecimal.ZERO;
        for(var node:nodes) {
            Objects.requireNonNull(node);
            if(!seen.add(node.address()))
                throw new IllegalArgumentException("Duplicate water source in audit");
            input=input.add(BigDecimal.valueOf(node.localWater()));
        }
        if(seen.isEmpty()||!seen.equals(solved.discharge().keySet()))
            throw new IllegalArgumentException("Audit graph/result addresses differ");
        BigDecimal outlet=sum(solved.terminalOutflow().values());
        BigDecimal frontier=sum(solved.pendingOutflow().values());
        BigDecimal residual=input.subtract(outlet).subtract(frontier);
        BigDecimal scale=input.max(outlet.add(frontier));
        BigDecimal tolerance=scale.multiply(BigDecimal.valueOf(1e-10))
            .max(BigDecimal.valueOf(1e-12));
        if(residual.abs().compareTo(tolerance)>0)
            throw new IllegalArgumentException("Hydrology water conservation violated");
        return new Budget(finite(input),finite(outlet),finite(frontier),
                          finite(residual));
    }

    private static BigDecimal sum(Collection<Double> amounts) {
        BigDecimal total=BigDecimal.ZERO;
        for(var amount:amounts) {
            if(amount==null||!Double.isFinite(amount)||amount<0)
                throw new IllegalArgumentException("Invalid water output");
            total=total.add(BigDecimal.valueOf(amount));
        }
        return total;
    }

    private static double finite(BigDecimal value) {
        double converted=value.doubleValue();
        if(!Double.isFinite(converted))
            throw new IllegalArgumentException("Water budget overflow");
        return converted;
    }
}
