package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiStrategicPlanWire(
        @JsonProperty("candidates") List<AiActionProposal> candidates,
        @JsonProperty("memory_update") AiMemoryUpdateWire memoryUpdate
) {
    public AiStrategicPlan toDomain() {
        return new AiStrategicPlan(
                candidates,
                memoryUpdate == null
                        ? new AiMemoryUpdate("",null,null,null,null)
                        : memoryUpdate.toDomain()
        );
    }
}
