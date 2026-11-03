package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only, network-wide recovery planning for durable timed water transfers.
 * A transfer missing from every authenticated receiver is assigned to exactly
 * one receiver by its canonical physical destination, even across resolutions.
 *
 * This does NOT acknowledge senders or commit multiple receiver files. The
 * caller must supply the COMPLETE sender and receiver fleet and a complete,
 * immutable atlas. Each prepared receiver epoch requires its own local CAS;
 * concurrent changes elsewhere still require an external coordination protocol.
 */
public final class HydrologyTimedBoundaryNetworkRecoveryV2 {
    private HydrologyTimedBoundaryNetworkRecoveryV2() {}

    public record Plan(HydrologyTimedBoundaryNetworkAuditV2.Report audit,
            Map<String,List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>> missingByReceiver,
            Map<String,HydrologyCheckpointV2.Checkpoint> expectedCheckpoints) {
        public Plan {
            Objects.requireNonNull(audit);
            Objects.requireNonNull(missingByReceiver);
            Objects.requireNonNull(expectedCheckpoints);
            var ordered = new TreeMap<String,List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>>();
            var ids = new HashSet<String>();
            BigDecimal total = BigDecimal.ZERO;
            int count = 0;
            for (var entry : missingByReceiver.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank())
                    throw new IllegalArgumentException("Invalid recovery receiver");
                var transfers = new ArrayList<>(Objects.requireNonNull(entry.getValue()));
                transfers.sort(Comparator.comparing(HydrologyVerifiedTimedBoundaryV2.EdgeTransfer::id));
                for (var transfer : transfers) {
                    if (!ids.add(transfer.id()))
                        throw new IllegalArgumentException("Recovery transfer duplicated");
                    total = total.add(BigDecimal.valueOf(transfer.volumeM3()));
                    count++;
                }
                ordered.put(entry.getKey(),List.copyOf(transfers));
            }
            if (count != audit.missing() || total.compareTo(audit.missingM3()) != 0)
                throw new IllegalArgumentException("Recovery plan does not conserve missing water");
            if (!ordered.keySet().containsAll(expectedCheckpoints.keySet()) ||
                !expectedCheckpoints.keySet().containsAll(ordered.keySet()))
                throw new IllegalArgumentException("Recovery checkpoint coverage mismatch");
            missingByReceiver = Collections.unmodifiableMap(ordered);
            expectedCheckpoints = Collections.unmodifiableMap(new TreeMap<>(expectedCheckpoints));
        }
    }

    /** State-only planning; no receiver checkpoint is available to advance. */
    public static Plan plan(Collection<HydrologyTimedBoundarySenderV2.State> senders,
            Map<String,HydrologyTimedBoundaryNetworkAuditV2.Receiver> receivers,
            HydrologySphericalRouteAtlasV2 atlas) {
        return planInternal(senders,receivers,atlas,Map.of());
    }

    private static Plan planInternal(
            Collection<HydrologyTimedBoundarySenderV2.State> senders,
            Map<String,HydrologyTimedBoundaryNetworkAuditV2.Receiver> receivers,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<String,HydrologyCheckpointV2.Checkpoint> expected) {
        Objects.requireNonNull(senders);
        Objects.requireNonNull(receivers);
        var report = HydrologyTimedBoundaryNetworkAuditV2.audit(senders,receivers,atlas,true);
        if (report.unassigned() != 0)
            throw new IllegalStateException("Missing water has no authenticated receiver");
        var owners = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,String>();
        var observed = new HashSet<String>();
        var missing = new TreeMap<String,List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>>();
        for (var entry : receivers.entrySet()) {
            missing.put(entry.getKey(),new ArrayList<>());
            for (var address : entry.getValue().assigned())
                owners.put(HydrologyGlobalCatchmentsV2.coordinate(address),entry.getKey());
            observed.addAll(entry.getValue().transit().pending().keySet());
            observed.addAll(entry.getValue().transit().settled().keySet());
        }
        for (var sender : senders) {
            for (var transfer : sender.issued().values()) {
                if (observed.contains(transfer.id())) continue;
                String owner = owners.get(HydrologyGlobalCatchmentsV2.coordinate(
                    transfer.destination()));
                if (owner == null)
                    throw new IllegalStateException("Unassigned missing water");
                missing.get(owner).add(transfer);
            }
        }
        // State-only plans have no durable checkpoints. Preserve an explicit
        // null-free key set by passing no snapshots and allowing it here.
        return new Plan(report,missing,expected);
    }

    /**
     * Read each durable checkpoint once, reject aliases and mismatched epochs,
     * then plan from those exact snapshots. No files are changed.
     */
    public static Plan planCheckpoints(Map<String,Path> senderFiles,
            Map<String,HydrologyTimedBoundaryNetworkAuditV2.ReceiverFile> receiverFiles,
            HydrologySphericalRouteAtlasV2 atlas) throws IOException {
        Objects.requireNonNull(senderFiles);
        Objects.requireNonNull(receiverFiles);
        var paths = new ArrayList<Path>();
        var senders = new ArrayList<HydrologyTimedBoundarySenderV2.State>();
        for (var entry : new TreeMap<>(senderFiles).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid sender key");
            var checkpoint = HydrologyCheckpointStoreV2.load(distinct(entry.getValue(),paths));
            var sender = HydrologyTimedBoundarySenderV2.decode(checkpoint.worldState());
            if (sender.epoch() != checkpoint.epoch())
                throw new IllegalArgumentException("Sender checkpoint epoch mismatch");
            senders.add(sender);
        }
        var receivers = new TreeMap<String,HydrologyTimedBoundaryNetworkAuditV2.Receiver>();
        var expected = new TreeMap<String,HydrologyCheckpointV2.Checkpoint>();
        for (var entry : new TreeMap<>(receiverFiles).entrySet()) {
            var receiver = Objects.requireNonNull(entry.getValue());
            var checkpoint = HydrologyCheckpointStoreV2.load(distinct(receiver.checkpoint(),paths));
            var state = HydrologyTimedSeasonalEpochV2.decode(checkpoint.worldState());
            var transit = HydrologyTimedTransitLedgerV2.decode(state.transit());
            if (transit.tick() != checkpoint.epoch())
                throw new IllegalArgumentException("Receiver checkpoint epoch mismatch");
            receivers.put(entry.getKey(),new HydrologyTimedBoundaryNetworkAuditV2.Receiver(
                receiver.assigned(),transit));
            expected.put(entry.getKey(),checkpoint);
        }
        return planInternal(senders,receivers,atlas,expected);
    }

    private static Path distinct(Path file,List<Path> seen) throws IOException {
        var path = Objects.requireNonNull(file).toAbsolutePath().normalize();
        for (var previous : seen)
            if (previous.equals(path) || Files.isSameFile(previous,path))
                throw new IllegalArgumentException("Aliased network recovery checkpoint");
        seen.add(path);
        return path;
    }

    /**
     * Prepare ONE receiver's next season from a durable network plan.
     * The returned checkpoint may be saved using saveNext(file,expected,next).
     * No network-wide atomicity is implied, and a fresh plan is required after
     * any sender/receiver mutation.
     */
    public static HydrologyTimedSeasonalEpochV2.Prepared prepareUniform(
            Plan plan,String receiver,HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices,double durationSeconds) {
        var expected = checkpoint(plan,receiver);
        return HydrologyTimedSeasonalEpochV2.prepareStreamingUniformVerified(
            expected,atlas,forcing,lakes,loaded,plan.missingByReceiver().get(receiver),
            slices,durationSeconds);
    }

    /** Chronological lake forcing uses the same globally authenticated plan. */
    public static HydrologyTimedSeasonalEpochV2.Prepared prepareScheduled(
            Plan plan,String receiver,HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,double durationSeconds) {
        var expected = checkpoint(plan,receiver);
        return HydrologyTimedSeasonalEpochV2.prepareStreamingScheduledVerified(
            expected,atlas,forcing,lakes,schedules,loaded,
            plan.missingByReceiver().get(receiver),durationSeconds);
    }

    private static HydrologyCheckpointV2.Checkpoint checkpoint(Plan plan,String receiver) {
        Objects.requireNonNull(plan);
        var expected = plan.expectedCheckpoints().get(receiver);
        if (expected == null)
            throw new IllegalArgumentException("Receiver has no authenticated durable snapshot");
        return expected;
    }
}
