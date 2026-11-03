package com.wilddeck.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in, durable local sender debit and verified boundary-transfer journal.
 * Every new transfer debits an owned river-water inventory exactly once; its
 * source, destination, due tick and volume survive restart and replay.
 *
 * The initial inventory is caller-authenticated. A sender checkpoint is NOT
 * atomic with the independent receiver checkpoint, terrain, sediment, or
 * the physical source of that inventory. Retain the journal for recovery.
 */
public final class HydrologyTimedBoundarySenderV2 {
    private HydrologyTimedBoundarySenderV2() {}
    private static final String MAGIC = "WD-TIMED-BOUNDARY-SENDER-1";
    private static final int MAX_HISTORY = 100_000;
    private static final int MAX_PAYLOAD = 24 * 1024 * 1024;

    public record State(String world, long epoch,
            Map<GlobalHydrologyAddressV2.Sample,Double> availableM3,
            Map<String,HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> issued) {
        public State {
            if (world == null || world.isBlank() || world.length() > 4096 || epoch < 0)
                throw new IllegalArgumentException("Invalid sender world or epoch");
            Objects.requireNonNull(availableM3);
            Objects.requireNonNull(issued);
            var available = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            var physical = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
            for (var entry : availableM3.entrySet()) {
                var address = Objects.requireNonNull(entry.getKey());
                double volume = Objects.requireNonNull(entry.getValue());
                if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                    !physical.add(HydrologyGlobalCatchmentsV2.coordinate(address)) ||
                    !Double.isFinite(volume) || volume < 0)
                    throw new IllegalArgumentException("Duplicate or invalid sender water owner");
                available.put(address,volume);
            }
            var journal = new TreeMap<String,HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
            for (var entry : issued.entrySet()) {
                var transfer = Objects.requireNonNull(entry.getValue());
                if (!transfer.id().equals(entry.getKey()) ||
                    !HydrologySphericalTopologyV2.isCanonical(transfer.source()) ||
                    !HydrologySphericalTopologyV2.isCanonical(transfer.destination()))
                    throw new IllegalArgumentException("Invalid journaled river transfer");
                journal.put(entry.getKey(),transfer);
            }
            if (journal.size() > MAX_HISTORY || available.size() > MAX_HISTORY)
                throw new IllegalArgumentException("Sender journal capacity exceeded");
            availableM3 = Collections.unmodifiableMap(available);
            issued = Collections.unmodifiableMap(journal);
        }
    }

    public record Step(State state,
            List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> newlyIssued,
            BigDecimal beforeM3, BigDecimal debitedM3, BigDecimal afterM3) {
        public Step {
            Objects.requireNonNull(state);
            newlyIssued = List.copyOf(Objects.requireNonNull(newlyIssued));
            Objects.requireNonNull(beforeM3);
            Objects.requireNonNull(debitedM3);
            Objects.requireNonNull(afterM3);
            if (beforeM3.compareTo(debitedM3.add(afterM3)) != 0)
                throw new IllegalArgumentException("Sender debit violates conservation");
        }
    }

    public record Committed(Step step, HydrologyCheckpointV2.Checkpoint checkpoint) {
        public Committed {
            Objects.requireNonNull(step);
            Objects.requireNonNull(checkpoint);
        }
    }

    public static State empty(String world,
            Map<GlobalHydrologyAddressV2.Sample,Double> initialWater) {
        return new State(world,0,initialWater,Map.of());
    }

    /**
     * Only atlas-verified new IDs debit water. Identical replay does not debit
     * again, even after codec restart; conflicting replay fails closed.
     * Exact decimal accounting rejects double rounding that would erase a debit.
     */
    public static Step advance(State previous, HydrologySphericalRouteAtlasV2 atlas,
            Collection<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> outgoing) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(outgoing);
        if (previous.epoch() == Long.MAX_VALUE)
            throw new IllegalArgumentException("Sender epoch overflow");
        var unique = new TreeMap<String,HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
        for (var transfer : outgoing) {
            Objects.requireNonNull(transfer);
            var prior = unique.putIfAbsent(transfer.id(),transfer);
            if (prior != null && !prior.equals(transfer))
                throw new IllegalArgumentException("Conflicting outgoing river ID");
        }
        // Audit retained source provenance too: a different atlas cannot
        // silently reinterpret the journal after a topology change.
        HydrologyVerifiedTimedBoundaryV2.verify(atlas,previous.issued().values());
        HydrologyVerifiedTimedBoundaryV2.verify(atlas,unique.values());
        var available = new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        available.putAll(previous.availableM3());
        var issued = new TreeMap<>(previous.issued());
        var newlyIssued = new ArrayList<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
        var debitBySource = new TreeMap<GlobalHydrologyAddressV2.Sample,BigDecimal>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var transfer : unique.values()) {
            var old = issued.get(transfer.id());
            if (old != null) {
                if (!old.equals(transfer))
                    throw new IllegalArgumentException("Conflicting replayed sender transfer");
                continue;
            }
            if (!available.containsKey(transfer.source()))
                throw new IllegalArgumentException("Unowned sender water source");
            if (issued.size() >= MAX_HISTORY)
                throw new IllegalArgumentException("Sender journal capacity exceeded");
            issued.put(transfer.id(),transfer);
            newlyIssued.add(transfer);
            debitBySource.merge(transfer.source(),
                BigDecimal.valueOf(transfer.volumeM3()),BigDecimal::add);
        }
        BigDecimal before = sum(available.values());
        BigDecimal debited = BigDecimal.ZERO;
        for (var entry : debitBySource.entrySet()) {
            BigDecimal old = BigDecimal.valueOf(available.get(entry.getKey()));
            BigDecimal remaining = old.subtract(entry.getValue());
            if (remaining.signum() < 0)
                throw new IllegalArgumentException("Insufficient sender water");
            double converted = remaining.doubleValue();
            if (!Double.isFinite(converted) ||
                BigDecimal.valueOf(converted).compareTo(remaining) != 0)
                throw new IllegalArgumentException("Sender debit would lose precision");
            available.put(entry.getKey(),converted);
            debited = debited.add(entry.getValue());
        }
        return new Step(new State(previous.world(),previous.epoch()+1,available,issued),
            newlyIssued,before,debited,sum(available.values()));
    }

    /**
     * Compare a persisted sender journal with the receiver's durable timed
     * ledger. Return only missing transfers for idempotent receiver replay.
     * A conflicting receiver ID fails closed. This is read-only recovery
     * planning, NOT a distributed acknowledgement or automatic delivery.
     */
    public static List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> missingAtReceiver(
            State sender, HydrologyTimedTransitLedgerV2.State receiver,
            HydrologySphericalRouteAtlasV2 atlas) {
        Objects.requireNonNull(sender);
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(atlas);
        return HydrologyTimedBoundaryReconciliationV2.audit(
            sender,receiver,atlas).missing();
    }

    /** Read both independent checkpoints without mutating either one. */
    public static List<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> missingAtReceiver(
            Path senderFile, Path receiverFile,
            HydrologySphericalRouteAtlasV2 atlas) throws IOException {
        var senderCheckpoint = HydrologyCheckpointStoreV2.load(senderFile);
        var sender = decode(senderCheckpoint.worldState());
        if (sender.epoch() != senderCheckpoint.epoch())
            throw new IllegalArgumentException("Sender recovery checkpoint epoch mismatch");
        var receiverCheckpoint = HydrologyCheckpointStoreV2.load(receiverFile);
        var receiver = HydrologyTimedTransitLedgerV2.decode(
            HydrologyTimedSeasonalEpochV2.decode(
                receiverCheckpoint.worldState()).transit());
        return missingAtReceiver(sender,receiver,atlas);
    }

    /** Persist the debit and journal together, before transmitting new IDs. */
    public static Committed commit(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Collection<HydrologyVerifiedTimedBoundaryV2.EdgeTransfer> outgoing)
            throws IOException {
        Objects.requireNonNull(expected);
        var previous = decode(expected.worldState());
        if (previous.epoch() != expected.epoch())
            throw new IllegalArgumentException("Sender checkpoint epoch mismatch");
        var step = advance(previous,atlas,outgoing);
        var next = new HydrologyCheckpointV2.Checkpoint(step.state().epoch(),
            encode(step.state()),expected.inboxState());
        HydrologyCheckpointStoreV2.saveNext(file,expected,next);
        return new Committed(step,next);
    }

    /**
     * Geodesic travel planning and local sender debit share one checked call.
     * Caller-supplied velocity remains an uncalibrated physical assumption.
     */
    public static Committed commitJourneys(Path file,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Collection<HydrologyTimedBoundaryTravelV2.Journey> journeys,
            double radiusMeters, double secondsPerTick) throws IOException {
        var planned = HydrologyTimedBoundaryTravelV2.plan(
            atlas,journeys,radiusMeters,secondsPerTick);
        return commit(file,expected,atlas,planned.edges());
    }

    public static HydrologyCheckpointV2.Checkpoint initialize(Path file,State initial)
            throws IOException {
        Objects.requireNonNull(initial);
        if (initial.epoch() != 0 || !initial.issued().isEmpty())
            throw new IllegalArgumentException("Sender initialization must be fresh");
        var checkpoint = HydrologyCheckpointV2.capture(0,encode(initial),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(file,checkpoint);
        return checkpoint;
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var out = new StringBuilder(MAGIC).append('\n')
            .append(Base64.getEncoder().encodeToString(
                state.world().getBytes(StandardCharsets.UTF_8))).append('\n')
            .append(state.epoch()).append('\n')
            .append(state.availableM3().size()).append('\n')
            .append(state.issued().size()).append('\n');
        for (var entry : state.availableM3().entrySet()) {
            var a = entry.getKey();
            out.append("A|").append(a.level()).append('|').append(a.divisions())
                .append('|').append(a.x()).append('|').append(a.y()).append('|')
                .append(Double.toHexString(entry.getValue())).append('\n');
        }
        for (var t : state.issued().values()) {
            var a=t.source(); var b=t.destination();
            out.append("I|").append(t.id()).append('|').append(a.level())
                .append('|').append(a.divisions()).append('|').append(a.x())
                .append('|').append(a.y()).append('|').append(b.x())
                .append('|').append(b.y()).append('|').append(t.dueTick())
                .append('|').append(Double.toHexString(t.volumeM3())).append('\n');
        }
        if (out.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Sender journal payload too large");
        return out.toString();
    }

    public static State decode(String payload) {
        Objects.requireNonNull(payload);
        if (payload.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Sender journal payload too large");
        var lines = payload.split("\\n",-1);
        if (lines.length < 6 || !MAGIC.equals(lines[0]) ||
            !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Invalid sender journal payload");
        try {
            String world = new String(Base64.getDecoder().decode(lines[1]),
                StandardCharsets.UTF_8);
            long epoch = Long.parseLong(lines[2]);
            int aCount=Integer.parseInt(lines[3]), iCount=Integer.parseInt(lines[4]);
            if (aCount < 0 || iCount < 0 || aCount > MAX_HISTORY ||
                iCount > MAX_HISTORY || lines.length != 6L+aCount+iCount)
                throw new IllegalArgumentException("Invalid sender journal counts");
            var available = new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
            var issued = new HashMap<String,HydrologyVerifiedTimedBoundaryV2.EdgeTransfer>();
            for (int i=0;i<aCount;i++) {
                var f=lines[i+5].split("\\|",-1);
                if (f.length != 6 || !f[0].equals("A"))
                    throw new IllegalArgumentException("Invalid sender inventory row");
                var a=new GlobalHydrologyAddressV2.Sample(
                    Integer.parseInt(f[1]),Integer.parseInt(f[2]),
                    Long.parseLong(f[3]),Long.parseLong(f[4]));
                if (available.putIfAbsent(a,Double.parseDouble(f[5])) != null)
                    throw new IllegalArgumentException("Duplicate sender inventory row");
            }
            for (int i=0;i<iCount;i++) {
                var f=lines[i+5+aCount].split("\\|",-1);
                if (f.length != 10 || !f[0].equals("I"))
                    throw new IllegalArgumentException("Invalid sender transfer row");
                int level=Integer.parseInt(f[2]), divisions=Integer.parseInt(f[3]);
                var source=new GlobalHydrologyAddressV2.Sample(level,divisions,
                    Long.parseLong(f[4]),Long.parseLong(f[5]));
                var destination=new GlobalHydrologyAddressV2.Sample(level,divisions,
                    Long.parseLong(f[6]),Long.parseLong(f[7]));
                var transfer=new HydrologyVerifiedTimedBoundaryV2.EdgeTransfer(
                    f[1],source,destination,Long.parseLong(f[8]),
                    Double.parseDouble(f[9]));
                if (issued.putIfAbsent(transfer.id(),transfer) != null)
                    throw new IllegalArgumentException("Duplicate sender transfer row");
            }
            var state = new State(world,epoch,available,issued);
            if (!encode(state).equals(payload))
                throw new IllegalArgumentException("Noncanonical sender journal payload");
            return state;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed sender journal number",ex);
        }
    }

    private static BigDecimal sum(Collection<Double> volumes) {
        BigDecimal total=BigDecimal.ZERO;
        for (var volume : volumes) total=total.add(BigDecimal.valueOf(volume));
        return total;
    }
}
