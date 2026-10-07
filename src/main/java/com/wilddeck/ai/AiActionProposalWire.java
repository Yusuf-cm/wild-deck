package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiActionProposalWire(
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
    public AiActionProposal toDomain() {
        return new AiActionProposal(
                kind,
                blankToNull(verb),
                sourceCardIds,
                targetCardIds,
                blankToNull(targetPlayerId),
                blankToNull(cardId),
                hiddenPlay,
                message,
                strategicSummary,
                confidence
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
