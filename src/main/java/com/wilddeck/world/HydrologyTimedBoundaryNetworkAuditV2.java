package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only, network-wide audit of timed water across independently persisted
 * senders and receivers. Unlike a single-receiver fleet audit, a transfer may
 * be destined for any ONE of several receivers, but must never be present in
 * two. Physical ownership is compared across spherical sampling resolutions.
 *
 * Caller must authenticate the COMPLETE sender/receiver inventory, assigned
 * geography and route atlas. Settled water is only journal evidence, NOT proof
 * of its later conservation in lakes, rivers or oceans. No distributed lock,
 * acknowledgement, or transaction is provided.
 */
public final class HydrologyTimedBoundaryNetworkAuditV2 {
    private HydrologyTimedBoundaryNetworkAuditV2() {}

    public record Receiver(Set<GlobalHydrologyAddressV2.Sample> assigned,
                           HydrologyTimedTransitLedgerV2.State transit) {
        public Receiver {
            assigned = Set.copyOf(Objects.requireNonNull(assigned));
            Objects.requireNonNull(transit);
        }
    }

    public record ReceiverFile(Path checkpoint,
                               Set<GlobalHydrologyAddressV2.Sample> assigned) {
        public ReceiverFile {
            Objects.requireNonNull(checkpoint);
            assigned = Set.copyOf(Objects.requireNonNull(assigned));
        }
    }

    public record Report(int senders, int receivers, int pending, int settled,
                         int missing, int unassigned, int foreign,
                         BigDecimal availableM3, BigDecimal issuedM3,
                         BigDecimal pendingM3, BigDecimal settledM3,
                         BigDecimal missingM3) {
        public Report {
            if (senders < 0 || receivers < 0 || pending < 0 || settled < 0 ||
                missing < 0 || unassigned < 0 || foreign < 0)
                throw new IllegalArgumentException("Invalid network transfer counts");
            Objects.requireNonNull(availableM3);
            Objects.requireNonNull(issuedM3);
            Objects.requireNonNull(pendingM3);
            Objects.requireNonNull(settledM3);
            Objects.requireNonNull(missingM3);
            if (issuedM3.compareTo(pendingM3.add(settledM3).add(missingM3)) != 0)
                throw new IllegalArgumentException("Timed network water not conserved");
        }

        /** Only journal/transport quiescence, not a physical water budget. */
        public boolean quiescent() {
            return pending == 0 && missing == 0 && unassigned == 0 && foreign == 0;
        }
    }

    public static Report audit(
            Collection<HydrologyTimedBoundarySenderV2.State> senders,
            Map<String,Receiver> receivers,
            HydrologySphericalRouteAtlasV2 atlas,
            boolean strictReceiverCoverage) {
        Objects.requireNonNull(senders);
        Objects.requireNonNull(receivers);
        Objects.requireNonNull(atlas);
        if (receivers.isEmpty())
            throw new IllegalArgumentException("No authenticated water receivers");
        var physicalOwners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var destinationOwner = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,String>();
        var observed = new TreeMap<String,HydrologyTimedTransitLedgerV2.Transfer>();
        var observedPending = new HashSet<String>();
        var observedSettled = new HashSet<String>();
        String world = null;
        for (var entry : new TreeMap<>(receivers).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid water receiver key");
            var receiver = Objects.requireNonNull(entry.getValue());
            var transit = receiver.transit();
            if (world == null) world = transit.world();
            if (!world.equals(transit.world()))
                throw new IllegalArgumentException("Receiver world mismatch");
            for (var address : receiver.assigned()) {
                if (!HydrologySphericalTopologyV2.isCanonical(address))
                    throw new IllegalArgumentException("Noncanonical receiver ownership");
                var coordinate = HydrologyGlobalCatchmentsV2.coordinate(address);
                if (destinationOwner.putIfAbsent(coordinate,entry.getKey()) != null)
                    throw new IllegalArgumentException("Duplicate physical receiver ownership");
            }
            for (var transfer : transit.pending().values()) {
                verifyReceiverTransfer(entry.getKey(),transfer,destinationOwner);
                if (observed.putIfAbsent(transfer.id(),transfer) != null)
                    throw new IllegalArgumentException("Transfer duplicated across receivers");
                observedPending.add(transfer.id());
            }
            for (var transfer : transit.settled().values()) {
                verifyReceiverTransfer(entry.getKey(),transfer,destinationOwner);
                if (observed.putIfAbsent(transfer.id(),transfer) != null)
                    throw new IllegalArgumentException("Transfer duplicated across receivers");
                observedSettled.add(transfer.id());
            }
        }
        var issued = new TreeMap<String,HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
        BigDecimal available = BigDecimal.ZERO, issuedVolume = BigDecimal.ZERO;
        int senderCount = 0;
        for (var sender : senders) {
            Objects.requireNonNull(sender);
            if (!sender.world().equals(world))
                throw new IllegalArgumentException("Sender world mismatch");
            for (var entry : sender.availableM3().entrySet()) {
                if (!physicalOwners.add(HydrologyGlobalCatchmentsV2.coordinate(entry.getKey())))
                    throw new IllegalArgumentException("Duplicate physical sender ownership");
                available = available.add(BigDecimal.valueOf(entry.getValue()));
            }
            HydrologyVerifiedTimedBoundaryV2.verify(atlas,sender.issued().values());
            for (var edge : sender.issued().values()) {
                if (!sender.availableM3().containsKey(edge.source()))
                    throw new IllegalArgumentException("Unowned issued water source");
                if (issued.putIfAbsent(edge.id(),edge) != null)
                    throw new IllegalArgumentException("Transfer ID issued by multiple senders");
                issuedVolume = issuedVolume.add(BigDecimal.valueOf(edge.volumeM3()));
            }
            senderCount++;
        }
        BigDecimal pendingVolume = BigDecimal.ZERO, settledVolume = BigDecimal.ZERO,
            missingVolume = BigDecimal.ZERO;
        int pending = 0, settled = 0, missing = 0, unassigned = 0;
        for (var edge : issued.values()) {
            var expected = new HydrologyTimedTransitLedgerV2.Transfer(
                edge.id(),HydrologyGlobalCatchmentsV2.coordinate(edge.destination()),
                edge.dueTick(),edge.volumeM3());
            if (!destinationOwner.containsKey(expected.destination())) unassigned++;
            var actual = observed.get(edge.id());
            if (actual != null && !actual.equals(expected))
                throw new IllegalArgumentException("Receiver transfer conflicts with sender");
            var volume = BigDecimal.valueOf(edge.volumeM3());
            if (observedPending.contains(edge.id())) {
                pending++;
                pendingVolume = pendingVolume.add(volume);
            } else if (observedSettled.contains(edge.id())) {
                settled++;
                settledVolume = settledVolume.add(volume);
            } else {
                missing++;
                missingVolume = missingVolume.add(volume);
            }
        }
        int foreign = 0;
        for (var id : observed.keySet())
            if (!issued.containsKey(id)) foreign++;
        if (strictReceiverCoverage && foreign != 0)
            throw new IllegalArgumentException("Unjournaled receiver water");
        return new Report(senderCount,receivers.size(),pending,settled,
            missing,unassigned,foreign,available,issuedVolume,pendingVolume,
            settledVolume,missingVolume);
    }

    private static void verifyReceiverTransfer(String receiver,
            HydrologyTimedTransitLedgerV2.Transfer transfer,
            Map<HydrologyGlobalCatchmentsV2.Coordinate,String> owners) {
        // Physical ownership must be local to this receiver, not merely
        // somewhere in the network. Constant-time lookup avoids quadratic
        // scans for large streaming receiver journals.
        if (!receiver.equals(owners.get(transfer.destination())))
            throw new IllegalArgumentException("Transfer held by wrong physical receiver");
    }

    /** Check every durable checkpoint and reject physical file aliases. */
    public static Report auditCheckpoints(
            Map<String,Path> senderFiles,
            Map<String,ReceiverFile> receiverFiles,
            HydrologySphericalRouteAtlasV2 atlas,
            boolean strictReceiverCoverage) throws IOException {
        Objects.requireNonNull(senderFiles);
        Objects.requireNonNull(receiverFiles);
        var paths = new ArrayList<Path>();
        var senders = new ArrayList<HydrologyTimedBoundarySenderV2.State>();
        for (var entry : new TreeMap<>(senderFiles).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("Invalid sender key");
            var path = distinct(entry.getValue(),paths);
            var checkpoint = HydrologyCheckpointStoreV2.load(path);
            var state = HydrologyTimedBoundarySenderV2.decode(checkpoint.worldState());
            if (state.epoch() != checkpoint.epoch())
                throw new IllegalArgumentException("Sender epoch mismatch");
            senders.add(state);
        }
        var receivers = new TreeMap<String,Receiver>();
        for (var entry : new TreeMap<>(receiverFiles).entrySet()) {
            var path = distinct(entry.getValue().checkpoint(),paths);
            var checkpoint = HydrologyCheckpointStoreV2.load(path);
            var outer = HydrologyTimedSeasonalEpochV2.decode(checkpoint.worldState());
            var transit = HydrologyTimedTransitLedgerV2.decode(outer.transit());
            if (transit.tick() != checkpoint.epoch())
                throw new IllegalArgumentException("Receiver epoch mismatch");
            receivers.put(entry.getKey(),new Receiver(entry.getValue().assigned(),transit));
        }
        return audit(senders,receivers,atlas,strictReceiverCoverage);
    }

    private static Path distinct(Path file,List<Path> paths) throws IOException {
        var normalized = Objects.requireNonNull(file).toAbsolutePath().normalize();
        for (var previous : paths)
            if (previous.equals(normalized) || Files.isSameFile(previous,normalized))
                throw new IllegalArgumentException("Aliased water checkpoint file");
        paths.add(normalized);
        return normalized;
    }

    /** Reject topology changes while ANY authenticated water remains in flight. */
    public static Report requireQuiescent(
            Map<String,Path> senderFiles,
            Map<String,ReceiverFile> receiverFiles,
            HydrologySphericalRouteAtlasV2 atlas) throws IOException {
        var report = auditCheckpoints(senderFiles,receiverFiles,atlas,true);
        if (!report.quiescent())
            throw new IllegalStateException("Unsettled timed water blocks topology change");
        return report;
    }

    /**
     * More restrictive route-change preflight: no outstanding transfers,
     * standing lake/frontier water, snow or soil stores, or unconsumed inbox.
     * Discharged ocean water is historical and need not be zero. This is a
     * read-only safety gate, NOT a license to mutate without a global lock or
     * a guarantee that a previously settled volume was physically conserved.
     */
    public static Report requireDryForTopologyChange(
            Map<String,Path> senderFiles,
            Map<String,ReceiverFile> receiverFiles,
            HydrologySphericalRouteAtlasV2 atlas) throws IOException {
        var report = requireQuiescent(senderFiles,receiverFiles,atlas);
        for (var receiver : receiverFiles.values()) {
            var checkpoint = HydrologyCheckpointStoreV2.load(receiver.checkpoint());
            var timed = HydrologyTimedSeasonalEpochV2.decode(checkpoint.worldState());
            var seasonal = HydrologySeasonalSnowSoilWaterEpochV2.decode(timed.seasonal());
            var topology = HydrologySeasonalTopologyEpochV2.decode(seasonal.topologyState());
            var water = HydrologySeasonalOutletSettlementV2.decode(topology.waterState()).lakes();
            var snow = HydrologySnowSoilStateV2.decode(seasonal.snowState());
            if (water.lakeStorageM3().values().stream().anyMatch(v -> v != 0) ||
                water.pendingM3().values().stream().anyMatch(v -> v != 0) ||
                snow.cells().values().stream().anyMatch(c ->
                    c.snowDepth() != 0 || c.soilDepth() != 0))
                throw new IllegalStateException("Stored water blocks dry topology change");
            var inbox = HydrologyCheckpointV2.restoreInbox(checkpoint).snapshot();
            if (!inbox.delivered().containsAll(inbox.receipts().keySet()))
                throw new IllegalStateException("Unconsumed water receipt blocks topology change");
        }
        return report;
    }
}
