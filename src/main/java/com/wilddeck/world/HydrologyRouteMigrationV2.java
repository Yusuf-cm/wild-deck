package com.wilddeck.world;

import java.util.*;

/**
 * Read-only, bounded-window audit of water already delivered to a geographic
 * landing when its downstream path changes between two terrain epochs.
 * This checks complete in-window paths to caller-verified outlets; it does
 * not authenticate an external atlas, change lake storage, or move receipts.
 */
public final class HydrologyRouteMigrationV2 {
    private HydrologyRouteMigrationV2() {}

    public record Audit(Map<Integer,Integer> previousOutlets,
                        Map<Integer,Integer> nextOutlets,
                        Set<Integer> changedVertices) {
        public Audit {
            previousOutlets = Collections.unmodifiableMap(new TreeMap<>(previousOutlets));
            nextOutlets = Collections.unmodifiableMap(new TreeMap<>(nextOutlets));
            changedVertices = Collections.unmodifiableSet(new TreeSet<>(changedVertices));
            if (!previousOutlets.keySet().equals(nextOutlets.keySet()))
                throw new IllegalArgumentException("Mismatched landing ownership");
        }
    }

    public static Audit verify(int[] previous, int[] next,
                               Collection<Integer> verifiedOutlets,
                               Collection<Integer> receiptLandings) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(next);
        Objects.requireNonNull(verifiedOutlets);
        Objects.requireNonNull(receiptLandings);
        if (previous.length == 0 || previous.length != next.length)
            throw new IllegalArgumentException("Mismatched drainage windows");
        var outlets = new HashSet<Integer>();
        for (Integer outlet : verifiedOutlets) {
            if (outlet == null || outlet < 0 || outlet >= previous.length ||
                !outlets.add(outlet))
                throw new IllegalArgumentException("Invalid verified outlet");
        }
        var oldMouths = new TreeMap<Integer,Integer>();
        var newMouths = new TreeMap<Integer,Integer>();
        var changed = new TreeSet<Integer>();
        for (Integer landing : receiptLandings) {
            if (landing == null || landing < 0 || landing >= previous.length ||
                oldMouths.containsKey(landing))
                throw new IllegalArgumentException("Invalid or duplicate receipt landing");
            oldMouths.put(landing, trace(previous, landing, outlets, previous, next, changed));
            newMouths.put(landing, trace(next, landing, outlets, previous, next, changed));
        }
        return new Audit(oldMouths, newMouths, changed);
    }

    private static int trace(int[] path, int landing, Set<Integer> outlets,
                             int[] previous, int[] next, Set<Integer> changed) {
        var visited = new HashSet<Integer>();
        int cursor = landing;
        while (true) {
            if (cursor < 0 || cursor >= path.length || !visited.add(cursor))
                throw new IllegalStateException("Unresolved or cyclic receipt drainage");
            if (previous[cursor] != next[cursor]) changed.add(cursor);
            int downstream = path[cursor];
            if (downstream == -1) {
                if (!outlets.contains(cursor))
                    throw new IllegalStateException("Receipt drains to unverified outlet");
                return cursor;
            }
            if (downstream < 0 || downstream >= path.length)
                throw new IllegalStateException("Receipt leaves unverified drainage window");
            cursor = downstream;
        }
    }
}
