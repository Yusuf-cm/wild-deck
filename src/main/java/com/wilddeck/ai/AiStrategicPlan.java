package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiStrategicPlan(
        @JsonProperty("candidates") List<AiActionProposal> candidates,
        @JsonProperty("memory_update") AiMemoryUpdate memoryUpdate
) {
    public AiStrategicPlan {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        memoryUpdate = memoryUpdate == null
                ? new AiMemoryUpdate("",null,null,null,null)
                : memoryUpdate;
    }
}
