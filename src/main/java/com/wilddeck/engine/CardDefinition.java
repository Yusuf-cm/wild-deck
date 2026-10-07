package com.wilddeck.engine;

import java.util.*;

public record CardDefinition(
        String id,
        String name,
        String category,
        Set<String> properties,
        Set<String> capabilities,
        Integer strength,
        Map<ResourceType,Integer> cost,
        String notes
) {
    public CardDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(category);
        properties = normalize(properties);
        capabilities = normalize(capabilities);
        cost = cost == null ? Map.of() : Map.copyOf(cost);
        notes = notes == null ? "" : notes;
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value.trim().toUpperCase());
        }
        return Set.copyOf(out);
    }

    public boolean hasProperty(String value) {
        return properties.contains(value.trim().toUpperCase());
    }

    public boolean hasCapability(String value) {
        return capabilities.contains(value.trim().toUpperCase());
    }
}
