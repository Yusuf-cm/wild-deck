package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wilddeck.engine.ActionIntent;

import java.util.*;

public record AiActionProposal(
        @JsonProperty("kind") AiActionKind kind,
        @JsonProperty("verb") String verb,
        @JsonProperty("source_card_ids") List<String> sourceCardIds,
        @JsonProperty("target_card_ids") List<String> targetCardIds,
        @JsonProperty("target_player_id") String targetPlayerId,
        @JsonProperty("card_id") String cardId,
        @JsonProperty("hidden_play") boolean hiddenPlay,
        @JsonProperty("message") String message,
        @JsonProperty("strategic_summary") String strategicSummary,
        @JsonProperty("confidence") double confidence
) {
    public AiActionProposal {
        sourceCardIds = sourceCardIds == null ? List.of() : List.copyOf(sourceCardIds);
        targetCardIds = targetCardIds == null ? List.of() : List.copyOf(targetCardIds);
        message = message == null ? "" : message;
        strategicSummary = strategicSummary == null ? "" : strategicSummary;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public ActionIntent toIntent(String playerId) {
        if (kind != AiActionKind.WORLD_ACTION) {
            throw new IllegalStateException("only WORLD_ACTION converts to ActionIntent");
        }
        return new ActionIntent(playerId, verb, sourceCardIds, targetCardIds, targetPlayerId);
    }
}
