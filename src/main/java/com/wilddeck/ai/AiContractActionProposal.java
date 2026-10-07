package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiContractActionProposal(
        @JsonProperty("action_type") String actionType,
        @JsonProperty("target_player_id") String targetPlayerId,
        @JsonProperty("related_player_id") String relatedPlayerId,
        @JsonProperty("contract_id") String contractId,
        @JsonProperty("topic_key") String topicKey,
        @JsonProperty("recipient_player_id") String recipientPlayerId,
        @JsonProperty("consent_player_ids") List<String> consentPlayerIds
) {}
