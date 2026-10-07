package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiPlayerScore(
        @JsonProperty("player_id") String playerId,
        @JsonProperty("score") double score
) {}
