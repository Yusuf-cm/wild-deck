package com.wilddeck.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AiMemoryStore {
    private final Map<String,AiMemoryState> memory = new ConcurrentHashMap<>();

    public AiMemoryState get(String playerId) {
        return memory.getOrDefault(playerId,AiMemoryState.empty());
    }

    public void put(String playerId, AiMemoryState state) {
        memory.put(Objects.requireNonNull(playerId),Objects.requireNonNull(state));
    }

    public Map<String,AiMemoryState> snapshot() {
        return Map.copyOf(memory);
    }
}
