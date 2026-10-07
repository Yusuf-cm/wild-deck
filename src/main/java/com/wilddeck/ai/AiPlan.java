package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiPlan(
        @JsonProperty("candidates") List<AiActionProposal> candidates
) {
    public AiPlan {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
