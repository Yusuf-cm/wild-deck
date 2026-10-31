package com.wilddeck.world;

import java.util.*;

/**
 * Read-only identity changes between two fully verified drainage forests.
 * A catchment change is reported, never silently rewritten as the same basin.
 * This is not an in-flight water migration or a global drainage discovery.
 */
public final class HydrologyCatchmentChangeV2 {
    private HydrologyCatchmentChangeV2() {}

    public record Change(Set<GlobalHydrologyAddressV2.Sample> reassignedVertices,
                         Set<HydrologyGlobalCatchmentsV2.ReachId> addedReaches,
                         Set<HydrologyGlobalCatchmentsV2.ReachId> removedReaches) {
        public Change {
            Objects.requireNonNull(reassignedVertices);
            Objects.requireNonNull(addedReaches);
            Objects.requireNonNull(removedReaches);
            var vertices = new TreeSet<GlobalHydrologyAddressV2.Sample>(
                Comparator.comparingInt(GlobalHydrologyAddressV2.Sample::level)
                    .thenComparingInt(GlobalHydrologyAddressV2.Sample::divisions)
                    .thenComparingLong(GlobalHydrologyAddressV2.Sample::y)
                    .thenComparingLong(GlobalHydrologyAddressV2.Sample::x));
            vertices.addAll(reassignedVertices);
            reassignedVertices = Collections.unmodifiableSet(vertices);
            var reachOrder = Comparator.comparing(HydrologyGlobalCatchmentsV2.ReachId::stableKey);
            var added = new TreeSet<HydrologyGlobalCatchmentsV2.ReachId>(reachOrder);
            added.addAll(addedReaches);
            addedReaches = Collections.unmodifiableSet(added);
            var removed = new TreeSet<HydrologyGlobalCatchmentsV2.ReachId>(reachOrder);
            removed.addAll(removedReaches);
            removedReaches = Collections.unmodifiableSet(removed);
        }
        public boolean unchanged() {
            return reassignedVertices.isEmpty() && addedReaches.isEmpty() &&
                   removedReaches.isEmpty();
        }
    }

    public static Change compare(HydrologyGlobalCatchmentsV2.Result before,
                                 HydrologyGlobalCatchmentsV2.Result after) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        if (!before.catchments().keySet().equals(after.catchments().keySet()))
            throw new IllegalArgumentException("Different geographic ownership; reconcile before comparison");
        var reassigned = new HashSet<GlobalHydrologyAddressV2.Sample>();
        for (var entry : before.catchments().entrySet())
            if (!entry.getValue().equals(after.catchments().get(entry.getKey())))
                reassigned.add(entry.getKey());
        var added = new HashSet<>(after.reaches().values());
        added.removeAll(before.reaches().values());
        var removed = new HashSet<>(before.reaches().values());
        removed.removeAll(after.reaches().values());
        return new Change(reassigned, added, removed);
    }
}
