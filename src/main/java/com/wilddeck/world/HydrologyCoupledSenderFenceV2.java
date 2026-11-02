package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only, caller-scoped fence for a coupled water/sediment frontier drain.
 *
 * Water acknowledgments and sediment acknowledgments may advance while the
 * coordinator runs. The underlying sender histories, world epoch, and water
 * inbox must not change. This catches producer mutations during directory
 * preflight and between bounded batches; it is NOT an interprocess lock,
 * distributed fencing token, or globally atomic transaction.
 */
public final class HydrologyCoupledSenderFenceV2 {
    private HydrologyCoupledSenderFenceV2() {}

    private record Water(Path path, long epoch, String world,
            HydrologyMixedResolutionEpochV2.Snapshot inbox,
            Map<HydrologyMixedResolutionEpochV2.Receipt,Double> outbox,
            Set<HydrologyMixedResolutionEpochV2.Receipt> acknowledged) {
        Water {
            path = path.toAbsolutePath().normalize();
            outbox = Map.copyOf(outbox);
            acknowledged = Set.copyOf(acknowledged);
        }
    }

    private record Sediment(Path path,
            Map<HydrologySedimentReceiptInboxV2.Receipt,Double> receipts,
            Set<HydrologySedimentReceiptInboxV2.Receipt> acknowledged) {
        Sediment {
            path = path.toAbsolutePath().normalize();
            receipts = Map.copyOf(receipts);
            acknowledged = Set.copyOf(acknowledged);
        }
    }

    public record Fence(int waterSenders, int sedimentSenders,
            int waterReceipts, int sedimentReceipts,
            Map<String,Path> waterPaths, Map<String,Path> sedimentPaths) {
        public Fence {
            if (waterSenders < 0 || sedimentSenders < 0 ||
                waterReceipts < 0 || sedimentReceipts < 0)
                throw new IllegalArgumentException("Invalid sender fence counts");
            waterPaths = Collections.unmodifiableMap(new TreeMap<>(waterPaths));
            sedimentPaths = Collections.unmodifiableMap(new TreeMap<>(sedimentPaths));
        }
    }

    private static final class State {
        final TreeMap<String,Water> water = new TreeMap<>();
        final TreeMap<String,Sediment> sediment = new TreeMap<>();
    }

    private static State read(Map<String,Path> waterFiles,
            Map<String,Path> sedimentFiles) throws IOException {
        Objects.requireNonNull(waterFiles);
        Objects.requireNonNull(sedimentFiles);
        var state = new State();
        for (var entry : waterFiles.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey());
            if (name.isBlank()) throw new IllegalArgumentException("Blank water sender name");
            Path path = Objects.requireNonNull(entry.getValue());
            var checkpoint = HydrologyMixedResolutionCheckpointStoreV2.load(path);
            state.water.put(name,new Water(path,checkpoint.epoch(),
                checkpoint.worldState(),checkpoint.inbox(),
                checkpoint.outbox(),checkpoint.acknowledged()));
        }
        for (var entry : sedimentFiles.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey());
            if (name.isBlank()) throw new IllegalArgumentException("Blank sediment sender name");
            Path path = Objects.requireNonNull(entry.getValue());
            var journal = HydrologySedimentSenderOutboxV2.restore(
                HydrologyCheckpointStoreV2.load(path));
            state.sediment.put(name,new Sediment(path,journal.receipts(),
                journal.acknowledged()));
        }
        return state;
    }

    /** Immutable view of the sender journals at the start of a drain. */
    public static final class Snapshot {
        private final State initial;
        private Snapshot(State initial) { this.initial = initial; }

        public Fence report() {
            var waterPaths = new TreeMap<String,Path>();
            var sedimentPaths = new TreeMap<String,Path>();
            int waterReceipts = 0, sedimentReceipts = 0;
            for (var entry : initial.water.entrySet()) {
                waterPaths.put(entry.getKey(),entry.getValue().path());
                waterReceipts = Math.addExact(waterReceipts,entry.getValue().outbox().size());
            }
            for (var entry : initial.sediment.entrySet()) {
                sedimentPaths.put(entry.getKey(),entry.getValue().path());
                sedimentReceipts = Math.addExact(sedimentReceipts,
                    entry.getValue().receipts().size());
            }
            return new Fence(initial.water.size(),initial.sediment.size(),
                waterReceipts,sedimentReceipts,waterPaths,sedimentPaths);
        }

        /**
         * Only receipt acknowledgments are allowed to grow. New, removed or
         * changed exports, changed water inbox/world, changed file mappings,
         * and acknowledgment rollback all fail before the next batch.
         */
        public void verify(Map<String,Path> waterFiles,
                Map<String,Path> sedimentFiles) throws IOException {
            var current = read(waterFiles,sedimentFiles);
            if (!current.water.keySet().equals(initial.water.keySet()) ||
                !current.sediment.keySet().equals(initial.sediment.keySet()))
                throw new IOException("Hydrology sender inventory changed during drain");
            for (var entry : initial.water.entrySet()) {
                var previous = entry.getValue();
                var now = current.water.get(entry.getKey());
                if (!previous.path().equals(now.path()) ||
                    previous.epoch() != now.epoch() ||
                    !previous.world().equals(now.world()) ||
                    !previous.inbox().equals(now.inbox()) ||
                    !previous.outbox().equals(now.outbox()) ||
                    !now.acknowledged().containsAll(previous.acknowledged()))
                    throw new IOException("Water sender changed outside acknowledgments");
            }
            for (var entry : initial.sediment.entrySet()) {
                var previous = entry.getValue();
                var now = current.sediment.get(entry.getKey());
                if (!previous.path().equals(now.path()) ||
                    !previous.receipts().equals(now.receipts()) ||
                    !now.acknowledged().containsAll(previous.acknowledged()))
                    throw new IOException("Sediment sender changed outside acknowledgments");
            }
        }
    }

    public static Snapshot capture(Map<String,Path> waterFiles,
            Map<String,Path> sedimentFiles) throws IOException {
        return new Snapshot(read(waterFiles,sedimentFiles));
    }
}
