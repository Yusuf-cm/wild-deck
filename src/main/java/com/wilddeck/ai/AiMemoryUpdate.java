package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

public record AiMemoryUpdate(
        @JsonProperty("summary") String summary,
        @JsonProperty("trust_by_player") Map<String,Double> trustByPlayer,
        @JsonProperty("threat_by_player") Map<String,Double> threatByPlayer,
        @JsonProperty("suspicions") List<String> suspicions,
        @JsonProperty("plans") List<String> plans
) {
    public AiMemoryUpdate {
        summary = summary == null ? "" : summary;
        trustByPlayer = trustByPlayer == null ? Map.of() : Map.copyOf(trustByPlayer);
        threatByPlayer = threatByPlayer == null ? Map.of() : Map.copyOf(threatByPlayer);
        suspicions = suspicions == null ? List.of() : List.copyOf(suspicions);
        plans = plans == null ? List.of() : List.copyOf(plans);
    }

    public AiMemoryState toState(int round) {
        return new AiMemoryState(round,summary,trustByPlayer,threatByPlayer,suspicions,plans);
    }
}
