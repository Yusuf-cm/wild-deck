package com.wilddeck.world;

import java.io.*;
import java.util.*;
import java.util.Base64;

/**
 * Opt-in mixed-resolution receipt staging. Transfers have geographic, not tile,
 * identity; aliases cannot inject the same physical edge twice.
 *
 * State must be committed atomically with the world solve before acknowledging
 * incoming water. This is not a distributed consensus or authentication layer.
 */
public final class HydrologyMixedResolutionEpochV2 {
    private HydrologyMixedResolutionEpochV2() {}
    private static final int MAGIC = 0x4d524532; // MRE2
    private static final int MAX_RECEIPTS = 100_000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static HydrologyGlobalCatchmentsV2.Coordinate coordinate(GlobalHydrologyAddressV2.Sample s) {
        return HydrologyGlobalCatchmentsV2.coordinate(s);
    }

    public record Receipt(long epoch, GlobalHydrologyAddressV2.Sample from,
                          GlobalHydrologyAddressV2.Sample to) {
        public Receipt {
            if (epoch < 0) throw new IllegalArgumentException("Negative receipt epoch");
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            // Reuse the actual solver's cross-LOD adjacency and canonicality contract.
            new HydrologyMixedResolutionDischargeV2.Node(from, 0, to, false);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Receipt r && epoch == r.epoch &&
                coordinate(from).equals(coordinate(r.from)) &&
                coordinate(to).equals(coordinate(r.to));
        }
        @Override public int hashCode() {
            return Objects.hash(epoch, coordinate(from), coordinate(to));
        }
    }

    private static final Comparator<Receipt> ORDER =
        Comparator.comparingLong(Receipt::epoch)
            .thenComparing(r -> coordinate(r.from()).stableKey())
            .thenComparing(r -> coordinate(r.to()).stableKey());

    public record Snapshot(long advancedThrough, Map<Receipt, Double> receipts,
                           Set<Receipt> delivered) {
        public Snapshot {
            if (advancedThrough < -1) throw new IllegalArgumentException("Invalid committed epoch");
            Objects.requireNonNull(receipts);
            Objects.requireNonNull(delivered);
            if (receipts.size() > MAX_RECEIPTS)
                throw new IllegalArgumentException("Receipt budget exceeded");
            var ordered = new TreeMap<Receipt, Double>(ORDER);
            var origins = new HashMap<String, Receipt>();
            for (var entry : receipts.entrySet()) {
                var key = Objects.requireNonNull(entry.getKey());
                double amount = Objects.requireNonNull(entry.getValue());
                if (!Double.isFinite(amount) || amount < 0)
                    throw new IllegalArgumentException("Invalid water receipt");
                if (ordered.putIfAbsent(key, amount) != null)
                    throw new IllegalArgumentException("Duplicate geographic receipt");
                String origin = key.epoch() + ":" + coordinate(key.from()).stableKey();
                var prior = origins.putIfAbsent(origin, key);
                if (prior != null && !prior.equals(key))
                    throw new IllegalArgumentException("One source has multiple drainage destinations");
            }
            if (!ordered.keySet().containsAll(delivered))
                throw new IllegalArgumentException("Delivered receipt not in ledger");
            for (var key : delivered)
                if (key.epoch() > advancedThrough)
                    throw new IllegalArgumentException("Future receipt already delivered");
            receipts = Collections.unmodifiableMap(ordered);
            delivered = Set.copyOf(delivered);
        }
    }

    public record Prepared(HydrologyMixedResolutionDischargeV2.Result flow, Snapshot next) {
        public Prepared {
            Objects.requireNonNull(flow);
            Objects.requireNonNull(next);
        }
    }

    public static Snapshot empty() { return new Snapshot(-1, Map.of(), Set.of()); }

    /** Idempotent replay, including when the sender uses another LOD alias. */
    public static Snapshot receive(Snapshot state, Receipt key, double amount) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(key);
        if (!Double.isFinite(amount) || amount < 0)
            throw new IllegalArgumentException("Invalid receipt volume");
        var old = state.receipts().get(key);
        if (old != null) {
            if (Double.compare(old, amount) != 0)
                throw new IllegalArgumentException("Conflicting receipt replay");
            return state;
        }
        var copy = new HashMap<>(state.receipts());
        copy.put(key, amount);
        return new Snapshot(state.advancedThrough(), copy, state.delivered());
    }

    /**
     * Stage due receipts for loaded destinations. Old receipts addressed to
     * unloaded terrain remain pending. A loaded upstream owner and a receipt
     * for that same physical source are mutually exclusive, preventing replay
     * of water already routed through the loaded graph.
     */
    public static Prepared prepare(Snapshot state, long epoch,
            Collection<HydrologyMixedResolutionDischargeV2.Node> nodes,
            Set<GlobalHydrologyAddressV2.Sample> verifiedMouths) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(nodes);
        Objects.requireNonNull(verifiedMouths);
        if (epoch < 0 || epoch <= state.advancedThrough())
            throw new IllegalArgumentException("Epoch must advance");
        var owned = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var node : nodes) {
            Objects.requireNonNull(node);
            if (!owned.add(coordinate(node.address())))
                throw new IllegalArgumentException("Duplicate geographic water owner");
        }
        var incoming = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate, Double>();
        var delivered = new HashSet<>(state.delivered());
        for (var entry : state.receipts().entrySet()) {
            var key = entry.getKey();
            if (key.epoch() > epoch || delivered.contains(key) ||
                !owned.contains(coordinate(key.to()))) continue;
            if (owned.contains(coordinate(key.from())))
                throw new IllegalArgumentException("Imported source already loaded and routed");
            var dest = coordinate(key.to());
            double sum = incoming.getOrDefault(dest, 0.0) + entry.getValue();
            if (!Double.isFinite(sum)) throw new IllegalArgumentException("Receipt overflow");
            incoming.put(dest, sum);
            delivered.add(key);
        }
        var result = HydrologyMixedResolutionDischargeV2.solveWithIncoming(
            nodes, verifiedMouths, incoming);
        return new Prepared(result, new Snapshot(epoch, state.receipts(), delivered));
    }

    /** Deterministic versioned receipt snapshot for inclusion in an atomic world checkpoint. */
    public static String encode(Snapshot state) {
        Objects.requireNonNull(state);
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeLong(state.advancedThrough());
                out.writeInt(state.receipts().size());
                for (var entry : state.receipts().entrySet()) {
                    var key = entry.getKey();
                    out.writeLong(key.epoch());
                    write(out, key.from());
                    write(out, key.to());
                    out.writeDouble(entry.getValue());
                    out.writeBoolean(state.delivered().contains(key));
                }
            }
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Snapshot too large");
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Snapshot decode(String encoded) {
        Objects.requireNonNull(encoded);
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Snapshot too large");
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readInt() != MAGIC) throw new IllegalArgumentException("Unknown receipt format");
                long advanced = in.readLong();
                int count = in.readInt();
                if (count < 0 || count > MAX_RECEIPTS)
                    throw new IllegalArgumentException("Invalid receipt count");
                var receipts = new HashMap<Receipt, Double>();
                var delivered = new HashSet<Receipt>();
                for (int i = 0; i < count; i++) {
                    var key = new Receipt(in.readLong(), read(in), read(in));
                    double amount = in.readDouble();
                    if (receipts.putIfAbsent(key, amount) != null)
                        throw new IllegalArgumentException("Duplicate encoded receipt");
                    if (in.readBoolean()) delivered.add(key);
                }
                if (in.available() != 0) throw new IllegalArgumentException("Trailing snapshot data");
                return new Snapshot(advanced, receipts, delivered);
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid mixed-resolution receipt snapshot", ex);
        }
    }

    private static void write(DataOutputStream out, GlobalHydrologyAddressV2.Sample s)
            throws IOException {
        out.writeInt(s.level());
        out.writeInt(s.divisions());
        out.writeLong(s.x());
        out.writeLong(s.y());
    }
    private static GlobalHydrologyAddressV2.Sample read(DataInputStream in) throws IOException {
        return new GlobalHydrologyAddressV2.Sample(
            in.readInt(), in.readInt(), in.readLong(), in.readLong());
    }
}
