package com.wilddeck.engine;

import java.util.*;

public final class ResourcePool {
    private final EnumMap<ResourceType,Integer> values = new EnumMap<>(ResourceType.class);

    public ResourcePool() {
        for (ResourceType type : ResourceType.values()) values.put(type, 0);
    }

    public int get(ResourceType type) { return values.get(type); }

    public void add(ResourceType type, int amount) {
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0");
        values.merge(type, amount, Integer::sum);
    }

    public boolean canAfford(Map<ResourceType,Integer> cost) {
        return cost.entrySet().stream().allMatch(e -> get(e.getKey()) >= e.getValue());
    }

    public void spend(Map<ResourceType,Integer> cost) {
        if (!canAfford(cost)) throw new IllegalStateException("insufficient resources");
        cost.forEach((type, amount) -> values.put(type, get(type) - amount));
    }

    public Map<ResourceType,Integer> snapshot() { return Map.copyOf(values); }
}
