package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only, exact-decimal reconciliation of a durable sender journal against
 * one receiver's timed ledger. A missing transfer is still water in flight:
 * it must never be treated as lost, rainfall, or a second sender debit.
 *
 * The caller must supply the complete verified atlas and authentic checkpoint
 * states. This audit does not prove the sender's original inventory or provide
 * a distributed transaction, acknowledgement, or cross-sender uniqueness.
 */
public final class HydrologyTimedBoundaryReconciliationV2 {
    private HydrologyTimedBoundaryReconciliationV2() {}

    public record Audit(
            List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> missing,
            BigDecimal senderAvailableM3,
            BigDecimal senderIssuedM3,
            BigDecimal receiverPendingM3,
            BigDecimal receiverSettledM3,
            BigDecimal unreceivedM3,
            BigDecimal accountedM3) {
        public Audit {
            missing = List.copyOf(Objects.requireNonNull(missing));
            Objects.requireNonNull(senderAvailableM3);
            Objects.requireNonNull(senderIssuedM3);
            Objects.requireNonNull(receiverPendingM3);
            Objects.requireNonNull(receiverSettledM3);
            Objects.requireNonNull(unreceivedM3);
            Objects.requireNonNull(accountedM3);
            if (senderIssuedM3.compareTo(receiverPendingM3.add(
                    receiverSettledM3).add(unreceivedM3)) != 0 ||
                accountedM3.compareTo(senderAvailableM3.add(senderIssuedM3)) != 0)
                throw new IllegalArgumentException("Boundary reconciliation lost water");
        }
    }

    /**
     * Reconcile only IDs issued by this sender. Other legitimate senders may
     * have entries in the same receiver ledger; they are not counted here.
     * Conflicting IDs, mismatched worlds, and forged route edges fail closed.
     */
    public static Audit audit(HydrologyTimedBoundarySenderV2.State sender,
            HydrologyTimedTransitLedgerV2.State receiver,
            HydrologySphericalRouteAtlasV2 atlas) {
        Objects.requireNonNull(sender);
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(atlas);
        if (!sender.world().equals(receiver.world()))
            throw new IllegalArgumentException("Boundary reconciliation world mismatch");
        HydrologyVerifiedTimedBoundaryV2.verify(atlas,sender.issued().values());
        BigDecimal available = BigDecimal.ZERO;
        for (var volume : sender.availableM3().values())
            available = available.add(BigDecimal.valueOf(volume));
        BigDecimal issued = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal settled = BigDecimal.ZERO;
        BigDecimal missingVolume = BigDecimal.ZERO;
        var missing = new ArrayList<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
        for (var edge : sender.issued().values()) {
            if (!sender.availableM3().containsKey(edge.source()))
                throw new IllegalArgumentException("Journal source is not owned by sender");
            var expected = new HydrologyTimedTransitLedgerV2.Transfer(
                edge.id(),HydrologyGlobalCatchmentsV2.coordinate(edge.destination()),
                edge.dueTick(),edge.volumeM3());
            BigDecimal volume = BigDecimal.valueOf(edge.volumeM3());
            issued = issued.add(volume);
            var atPending = receiver.pending().get(edge.id());
            var atSettled = receiver.settled().get(edge.id());
            if (atPending != null && atSettled != null)
                throw new IllegalArgumentException("Receiver transfer double counted");
            var observed = atPending != null ? atPending : atSettled;
            if (observed != null && !expected.equals(observed))
                throw new IllegalArgumentException("Receiver transfer conflicts with sender");
            if (atPending != null) pending = pending.add(volume);
            else if (atSettled != null) settled = settled.add(volume);
            else {
                missing.add(edge);
                missingVolume = missingVolume.add(volume);
            }
        }
        return new Audit(missing,available,issued,pending,settled,missingVolume,
            available.add(pending).add(settled).add(missingVolume));
    }
}
