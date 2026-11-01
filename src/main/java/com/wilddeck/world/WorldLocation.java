package com.wilddeck.world;

import java.util.Objects;
import java.util.Set;

/** Immutable location description. No card or player state is stored here. */
public record WorldLocation(String id, String name, Set<String> properties) {
    public WorldLocation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        properties = Set.copyOf(Objects.requireNonNull(properties));
        if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("location id/name required");
    }

    public boolean hasProperty(String property) {
        return properties.contains(property);
    }
}
