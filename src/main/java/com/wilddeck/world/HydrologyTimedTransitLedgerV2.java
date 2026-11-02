package com.wilddeck.world;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in, noncommitting, exact-tick water-in-transit ledger. Transit due times
 * are absolute ticks, so an unloaded destination does not restart its travel
 * delay when loaded in a later season. The caller authenticates geographic
 * destinations, receipt IDs, travel times, and the loaded set. One returned
 * state and its deliveries MUST be checkpointed/consumed atomically by the
 * caller; this helper alone is not distributed exactly-once settlement.
 *
 * Tick duration is caller-defined, not a calibrated river velocity.
 */
public final class HydrologyTimedTransitLedgerV2 {
    private HydrologyTimedTransitLedgerV2() {}
    private static final String MAGIC = "WD-TIMED-TRANSIT-1";
    public static final int MAX_TRANSFERS = 100_000;
    private static final int MAX_PAYLOAD = 24 * 1024 * 1024;

    public record Transfer(String id, HydrologyGlobalCatchmentsV2.Coordinate destination,
                           long dueTick, double volumeM3) {
        public Transfer {
            if (id == null || !id.matches("[A-Za-z0-9._:-]{1,120}"))
                throw new IllegalArgumentException("Invalid water transit identity");
            Objects.requireNonNull(destination);
            if (dueTick < 0 || !Double.isFinite(volumeM3) || volumeM3 <= 0)
                throw new IllegalArgumentException("Invalid water transit volume or time");
        }
    }

    /** Settled IDs are deliberately retained to prevent replay after restart. */
    public record State(String world, long tick, Map<String,Transfer> pending,
                        Map<String,Transfer> settled) {
        public State {
            if (world == null || world.isBlank() || world.length() > 4096 || tick < 0)
                throw new IllegalArgumentException("Invalid transit world or tick");
            pending = checked(pending);
            settled = checked(settled);
            if ((long) pending.size() + settled.size() > MAX_TRANSFERS ||
                pending.keySet().stream().anyMatch(settled::containsKey) ||
                settled.values().stream().anyMatch(t -> t.dueTick() > tick))
                throw new IllegalArgumentException("Overlapping or oversized transit history");
        }
    }

    public record Step(State state,
                       Map<HydrologyGlobalCatchmentsV2.Coordinate,Double> releasedM3,
                       BigDecimal previousPendingM3, BigDecimal newlyQueuedM3,
                       BigDecimal releasedTotalM3, BigDecimal remainingM3) {
        public Step {
            Objects.requireNonNull(state);
            releasedM3 = Map.copyOf(Objects.requireNonNull(releasedM3));
            Objects.requireNonNull(previousPendingM3);
            Objects.requireNonNull(newlyQueuedM3);
            Objects.requireNonNull(releasedTotalM3);
            Objects.requireNonNull(remainingM3);
            if (previousPendingM3.add(newlyQueuedM3).compareTo(
                    releasedTotalM3.add(remainingM3)) != 0)
                throw new IllegalArgumentException("Transit conservation violated");
        }
    }

    public static State empty(String world) {
        return new State(world, 0, Map.of(), Map.of());
    }

    /**
     * Add authenticated transfers and release those due at/before targetTick
     * only when the destination is loaded. Repeated identical IDs are ignored;
     * a conflicting reuse of an ID is rejected, including after settlement.
     * A late-loaded destination retains its ORIGINAL dueTick in the checkpoint.
     */
    public static Step advance(State previous, Collection<Transfer> incoming,
                               long targetTick,
                               Set<HydrologyGlobalCatchmentsV2.Coordinate> loaded) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(incoming);
        Objects.requireNonNull(loaded);
        if (targetTick < previous.tick())
            throw new IllegalArgumentException("Transit time cannot move backward");
        for (var coordinate : loaded) Objects.requireNonNull(coordinate);
        var pending = new TreeMap<>(previous.pending());
        var settled = new TreeMap<>(previous.settled());
        var newTransfers = new TreeMap<String,Transfer>();
        for (var transfer : incoming) {
            Objects.requireNonNull(transfer);
            var old = newTransfers.putIfAbsent(transfer.id(), transfer);
            if (old != null && !old.equals(transfer))
                throw new IllegalArgumentException("Conflicting transit ID in batch");
        }
        BigDecimal newlyQueued = BigDecimal.ZERO;
        for (var transfer : newTransfers.values()) {
            var old = pending.containsKey(transfer.id()) ?
                pending.get(transfer.id()) : settled.get(transfer.id());
            if (old != null) {
                if (!old.equals(transfer))
                    throw new IllegalArgumentException("Conflicting replayed transit ID");
            } else {
                if ((long) pending.size() + settled.size() >= MAX_TRANSFERS)
                    throw new IllegalArgumentException("Transit ledger capacity exceeded");
                pending.put(transfer.id(), transfer);
                newlyQueued = newlyQueued.add(BigDecimal.valueOf(transfer.volumeM3()));
            }
        }
        var releasedExact = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,BigDecimal>();
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var transfer = entry.getValue();
            if (transfer.dueTick() <= targetTick && loaded.contains(transfer.destination())) {
                releasedExact.merge(transfer.destination(),
                    BigDecimal.valueOf(transfer.volumeM3()),BigDecimal::add);
                settled.put(entry.getKey(),transfer);
                iterator.remove();
            }
        }
        var released = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,Double>();
        BigDecimal releasedTotal = BigDecimal.ZERO;
        for (var entry : releasedExact.entrySet()) {
            released.put(entry.getKey(),exactDouble(entry.getValue()));
            releasedTotal = releasedTotal.add(entry.getValue());
        }
        var previousTotal = total(previous.pending().values());
        var remaining = total(pending.values());
        return new Step(new State(previous.world(),targetTick,pending,settled),
            released,previousTotal,newlyQueued,releasedTotal,remaining);
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var out = new StringBuilder(MAGIC).append('\n')
            .append(Base64.getEncoder().encodeToString(
                state.world().getBytes(StandardCharsets.UTF_8))).append('\n')
            .append(state.tick()).append('\n')
            .append(state.pending().size()).append('\n')
            .append(state.settled().size()).append('\n');
        for (var transfer : state.pending().values()) append(out,'P',transfer);
        for (var transfer : state.settled().values()) append(out,'S',transfer);
        if (out.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Transit payload too large");
        return out.toString();
    }

    public static State decode(String payload) {
        Objects.requireNonNull(payload);
        if (payload.length() > MAX_PAYLOAD)
            throw new IllegalArgumentException("Transit payload too large");
        var lines = payload.split("\\n",-1);
        if (lines.length < 6 || !MAGIC.equals(lines[0]) ||
            !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Invalid transit payload");
        try {
            var world = new String(Base64.getDecoder().decode(lines[1]),
                StandardCharsets.UTF_8);
            long tick = Long.parseLong(lines[2]);
            int p = Integer.parseInt(lines[3]), s = Integer.parseInt(lines[4]);
            if (p < 0 || s < 0 || (long)p+s > MAX_TRANSFERS ||
                lines.length != 6L+p+s)
                throw new IllegalArgumentException("Invalid transit record count");
            var pending = new TreeMap<String,Transfer>();
            var settled = new TreeMap<String,Transfer>();
            for (int i=0;i<p+s;i++) {
                var parts = lines[i+5].split("\\|",-1);
                if (parts.length != 8 ||
                    !(parts[0].equals(i<p?"P":"S")))
                    throw new IllegalArgumentException("Invalid transit record");
                var transfer = new Transfer(parts[1],
                    new HydrologyGlobalCatchmentsV2.Coordinate(
                        Long.parseLong(parts[2]),Long.parseLong(parts[3]),
                        Long.parseLong(parts[4]),Long.parseLong(parts[5])),
                    Long.parseLong(parts[6]),Double.parseDouble(parts[7]));
                var target = i<p ? pending : settled;
                if (target.putIfAbsent(transfer.id(),transfer) != null)
                    throw new IllegalArgumentException("Duplicate transit ID");
            }
            var state = new State(world,tick,pending,settled);
            if (!encode(state).equals(payload))
                throw new IllegalArgumentException("Noncanonical transit payload");
            return state;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed transit payload",ex);
        }
    }

    private static Map<String,Transfer> checked(Map<String,Transfer> source) {
        Objects.requireNonNull(source);
        var sorted = new TreeMap<String,Transfer>();
        for (var entry : source.entrySet()) {
            var transfer = Objects.requireNonNull(entry.getValue());
            if (!transfer.id().equals(entry.getKey()))
                throw new IllegalArgumentException("Transit ID and map key differ");
            sorted.put(entry.getKey(),transfer);
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static BigDecimal total(Collection<Transfer> transfers) {
        BigDecimal result = BigDecimal.ZERO;
        for (var transfer : transfers)
            result = result.add(BigDecimal.valueOf(transfer.volumeM3()));
        return result;
    }

    private static double exactDouble(BigDecimal value) {
        double result = value.doubleValue();
        if (!Double.isFinite(result) || BigDecimal.valueOf(result).compareTo(value) != 0)
            throw new IllegalArgumentException("Transit settlement would lose volume precision");
        return result;
    }

    private static void append(StringBuilder out,char status,Transfer t) {
        var c=t.destination();
        out.append(status).append('|').append(t.id()).append('|')
            .append(c.lonNumerator()).append('|').append(c.lonDenominator()).append('|')
            .append(c.latNumerator()).append('|').append(c.latDenominator()).append('|')
            .append(t.dueTick()).append('|')
            .append(Double.toHexString(t.volumeM3())).append('\n');
    }
}
