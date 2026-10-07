package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiContractClauseProposal(
        @JsonProperty("description") String description,
        @JsonProperty("bound_actor_ids") List<String> boundActorIds,
        @JsonProperty("action_types") List<String> actionTypes,
        @JsonProperty("target_player_ids") List<String> targetPlayerIds,
        @JsonProperty("related_player_ids") List<String> relatedPlayerIds,
        @JsonProperty("recipient_player_ids") List<String> recipientPlayerIds,
        @JsonProperty("topic_key") String topicKey,
        @JsonProperty("required_consent_from") List<String> requiredConsentFrom
) {}
