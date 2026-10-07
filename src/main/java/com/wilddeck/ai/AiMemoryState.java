package com.wilddeck.ai;

import java.util.*;

public record AiMemoryState(
        int lastUpdatedRound,
        String summary,
        Map<String,Double> trustByPlayer,
        Map<String,Double> threatByPlayer,
        List<String> suspicions,
        List<String> plans
) {
    public AiMemoryState {
        summary = summary == null ? "" : summary;
        trustByPlayer = trustByPlayer == null ? Map.of() : Map.copyOf(trustByPlayer);
        threatByPlayer = threatByPlayer == null ? Map.of() : Map.copyOf(threatByPlayer);
        suspicions = suspicions == null ? List.of() : List.copyOf(suspicions);
        plans = plans == null ? List.of() : List.copyOf(plans);
    }

    public static AiMemoryState empty() {
        return new AiMemoryState(0,"",Map.of(),Map.of(),List.of(),List.of());
    }
}
