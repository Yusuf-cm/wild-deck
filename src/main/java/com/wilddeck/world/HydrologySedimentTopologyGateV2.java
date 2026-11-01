package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only, fail-closed checkpoint gate before changing drainage topology.
 * Counts every durable sender receipt once, even if its destination is unloaded.
 * Acknowledgment alone is NOT proof that sediment was consumed by the receiver.
 *
 * Callers must supply the COMPLETE sender history and authoritative loaded
 * receiver ownership under a quiescent single-writer lifecycle. Passing this
 * gate does not prove that future transfers cannot arrive, does not authorize
 * receipt-history compaction, and is not a distributed transaction.
 */
public final class HydrologySedimentTopologyGateV2 {
    private HydrologySedimentTopologyGateV2() {}

    public record Report(int verified, int awaiting, int inTransit,
                         int pendingAcknowledgments, int pendingWater,
                         double verifiedMass, double unresolvedMass) {
        public Report {
            if (verified < 0 || awaiting < 0 || inTransit < 0 ||
                pendingAcknowledgments < 0 || pendingWater < 0 ||
                !Double.isFinite(verifiedMass) || verifiedMass < 0 ||
                !Double.isFinite(unresolvedMass) || unresolvedMass < 0)
                throw new IllegalArgumentException("Invalid hydrology topology gate report");
        }

        public boolean quiescent() {
            return awaiting == 0 && inTransit == 0 &&
                pendingAcknowledgments == 0 && pendingWater == 0;
        }
    }

    /**
     * Validate the complete loaded network before inspecting its actual
     * checksummed sender and receiver checkpoint files. Never writes to disk.
     */
    public static Report inspect(Map<String, Path> senderFiles,
            Map<String, HydrologySedimentLoadedRegionsV2.Receiver> loaded)
            throws IOException {
        Objects.requireNonNull(senderFiles);
        Objects.requireNonNull(loaded);
        // Also rejects physical source forks, cross-resolution shared vertices,
        // file aliases, altered histories and closed-epoch missing receipts.
        HydrologySedimentLoadedNetworkV2.plan(senderFiles, loaded);

        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            HydrologySedimentReceiptInboxV2.Snapshot>();
        int pendingWater = 0;
        for (var region : new TreeMap<>(loaded).entrySet()) {
            var receiver = region.getValue();
            var checkpoint = HydrologyCheckpointStoreV2.load(receiver.checkpoint());
            var sediment = HydrologySedimentCheckpointV2.decode(
                checkpoint.worldState()).sediment();
            var water = HydrologyInboxCodecV2.decode(checkpoint.inboxState());
            pendingWater = Math.addExact(pendingWater,
                water.receipts().size() - water.delivered().size());
            for (var vertex : receiver.ownedVertices()) {
                var location = HydrologyGlobalCatchmentsV2.coordinate(vertex);
                if (owners.putIfAbsent(location, sediment) != null)
                    throw new IllegalArgumentException("Overlapping sediment receiver ownership");
            }
        }

        int verified = 0, awaiting = 0, inTransit = 0, pendingAck = 0;
        BigDecimal verifiedMass = BigDecimal.ZERO, unresolvedMass = BigDecimal.ZERO;
        for (var sender : new TreeMap<>(senderFiles).entrySet()) {
            var outbox = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(sender.getValue()));
            for (var entry : outbox.receipts().entrySet()) {
                var receipt = entry.getKey();
                var mass = BigDecimal.valueOf(entry.getValue());
                var receiver = owners.get(
                    HydrologyGlobalCatchmentsV2.coordinate(receipt.to()));
                if (receiver == null || !receiver.receipts().containsKey(receipt)) {
                    if (outbox.acknowledged().contains(receipt))
                        throw new IllegalStateException(
                            "Acknowledged sediment missing from durable receiving history");
                    inTransit = Math.addExact(inTransit, 1);
                    unresolvedMass = unresolvedMass.add(mass);
                    continue;
                }
                if (Double.compare(receiver.receipts().get(receipt),
                        entry.getValue()) != 0)
                    throw new IllegalArgumentException("Conflicting sediment mass");
                if (!outbox.acknowledged().contains(receipt))
                    pendingAck = Math.addExact(pendingAck, 1);
                if (!receiver.delivered().contains(receipt)) {
                    awaiting = Math.addExact(awaiting, 1);
                    unresolvedMass = unresolvedMass.add(mass);
                } else {
                    verified = Math.addExact(verified, 1);
                    verifiedMass = verifiedMass.add(mass);
                }
            }
        }
        double settled = verifiedMass.doubleValue();
        double unresolved = unresolvedMass.doubleValue();
        if (!Double.isFinite(settled) || !Double.isFinite(unresolved))
            throw new IllegalArgumentException("Sediment topology gate mass overflow");
        return new Report(verified, awaiting, inTransit, pendingAck,
            pendingWater, settled, unresolved);
    }

    /**
     * Fail closed before a drainage/terrain route revision. A successful
     * result means no in-flight or unconsumed receipts in the supplied
     * snapshot, NOT permission to compact history or mutate without a lock.
     */
    public static Report requireQuiescent(Map<String, Path> senderFiles,
            Map<String, HydrologySedimentLoadedRegionsV2.Receiver> loaded)
            throws IOException {
        var report = inspect(senderFiles, loaded);
        if (!report.quiescent())
            throw new IllegalStateException(
                "Drainage topology change blocked by unsettled water or sediment");
        return report;
    }
}
