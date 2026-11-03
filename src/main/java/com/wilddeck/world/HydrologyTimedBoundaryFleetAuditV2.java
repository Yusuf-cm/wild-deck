package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only reconciliation of several independently checkpointed boundary
 * senders against one receiver transit ledger. This detects cross-sender ID
 * collisions and shared physical source ownership before any replay plan is
 * issued. It is not a distributed transaction or an audit of downstream
 * water after a transfer has been settled.
 */
public final class HydrologyTimedBoundaryFleetAuditV2 {
    private HydrologyTimedBoundaryFleetAuditV2() {}

    public record Audit(List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> missing,
            BigDecimal availableM3, BigDecimal issuedM3,
            BigDecimal pendingM3, BigDecimal settledM3,
            BigDecimal unreceivedM3, BigDecimal accountedM3,
            int senderCount, int foreignReceiverTransfers) {
        public Audit {
            missing = List.copyOf(Objects.requireNonNull(missing));
            Objects.requireNonNull(availableM3);
            Objects.requireNonNull(issuedM3);
            Objects.requireNonNull(pendingM3);
            Objects.requireNonNull(settledM3);
            Objects.requireNonNull(unreceivedM3);
            Objects.requireNonNull(accountedM3);
            if (senderCount < 0 || foreignReceiverTransfers < 0 ||
                issuedM3.compareTo(pendingM3.add(settledM3).add(unreceivedM3)) != 0 ||
                accountedM3.compareTo(availableM3.add(issuedM3)) != 0)
                throw new IllegalArgumentException("Fleet boundary water is not conserved");
        }
    }

    /**
     * Strict mode requires every pending/settled receiver ID to originate
     * from exactly one supplied sender. Non-strict mode permits unrelated
     * receiver transfers but never attributes their volume to these senders.
     *
     * The caller must authenticate the complete set of sender checkpoints,
     * receiver state, and immutable complete drainage atlas. Physical owners
     * are unique even when represented at different spherical resolutions.
     */
    public static Audit audit(Collection<HydrologyTimedBoundarySenderV2.State> senders,
            HydrologyTimedTransitLedgerV2.State receiver,
            HydrologySphericalRouteAtlasV2 atlas,
            boolean strictReceiverCoverage) {
        Objects.requireNonNull(senders);
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(atlas);
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var issuedIds = new HashSet<String>();
        var missing = new TreeMap<String,HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal issued = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal settled = BigDecimal.ZERO;
        BigDecimal unreceived = BigDecimal.ZERO;
        int count = 0;
        for (var sender : senders) {
            Objects.requireNonNull(sender);
            if (!sender.world().equals(receiver.world()))
                throw new IllegalArgumentException("Fleet sender world mismatch");
            for (var source : sender.availableM3().keySet()) {
                if (!owned.add(HydrologyGlobalCatchmentsV2.coordinate(source)))
                    throw new IllegalArgumentException("Shared physical water source across senders");
            }
            for (var id : sender.issued().keySet()) {
                if (!issuedIds.add(id))
                    throw new IllegalArgumentException("Transfer ID issued by multiple senders");
            }
            var individual = HydrologyTimedBoundaryReconciliationV2.audit(
                sender, receiver, atlas);
            available = available.add(individual.senderAvailableM3());
            issued = issued.add(individual.senderIssuedM3());
            pending = pending.add(individual.receiverPendingM3());
            settled = settled.add(individual.receiverSettledM3());
            unreceived = unreceived.add(individual.unreceivedM3());
            for (var edge : individual.missing()) missing.put(edge.id(),edge);
            count++;
        }
        int foreign = 0;
        for (var id : receiver.pending().keySet())
            if (!issuedIds.contains(id)) foreign++;
        for (var id : receiver.settled().keySet())
            if (!issuedIds.contains(id)) foreign++;
        if (strictReceiverCoverage && foreign != 0)
            throw new IllegalArgumentException("Receiver has unjournaled transfers");
        return new Audit(List.copyOf(missing.values()),available,issued,
            pending,settled,unreceived,available.add(issued),count,foreign);
    }
}
