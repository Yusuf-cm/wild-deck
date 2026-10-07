package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

public record AiMemoryUpdateWire(
        @JsonProperty("summary") String summary,
        @JsonProperty("trust_by_player") List<AiPlayerScore> trustByPlayer,
        @JsonProperty("threat_by_player") List<AiPlayerScore> threatByPlayer,
        @JsonProperty("suspicions") List<String> suspicions,
        @JsonProperty("plans") List<String> plans
) {
    public AiMemoryUpdate toDomain() {
        return new AiMemoryUpdate(
                summary,
                toMap(trustByPlayer),
                toMap(threatByPlayer),
                suspicions,
                plans
        );
    }

    private static Map<String,Double> toMap(List<AiPlayerScore> scores) {
        if (scores == null || scores.isEmpty()) return Map.of();
        LinkedHashMap<String,Double> out = new LinkedHashMap<>();
        for (AiPlayerScore entry : scores) {
            if (entry == null || entry.playerId() == null || entry.playerId().isBlank()) continue;
            out.put(entry.playerId(),Math.max(0.0,Math.min(1.0,entry.score())));
        }
        return Map.copyOf(out);
    }
}
