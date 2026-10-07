package com.wilddeck.engine;

import java.util.*;

/**
 * Parameterized contract restriction.
 *
 * Empty selector sets mean "any". A clause may be bypassed only when all
 * required consent-givers are present in the action request's consent set.
 */
public record CustomContractClause(
        String id,
        ClauseEffect effect,
        String description,
        Set<String> boundActorIds,
        Set<ContractActionType> actionTypes,
        Set<String> targetPlayerIds,
        Set<String> relatedPlayerIds,
        Set<String> recipientPlayerIds,
        String topicKey,
        Set<String> requiredConsentFrom
) {
    public CustomContractClause(
            ClauseEffect effect,
            String description,
            Set<String> boundActorIds,
            Set<ContractActionType> actionTypes,
            Set<String> targetPlayerIds,
            Set<String> relatedPlayerIds,
            Set<String> recipientPlayerIds,
            String topicKey,
            Set<String> requiredConsentFrom
    ) {
        this(
                UUID.randomUUID().toString(),
                effect,
                description,
                boundActorIds,
                actionTypes,
                targetPlayerIds,
                relatedPlayerIds,
                recipientPlayerIds,
                topicKey,
                requiredConsentFrom
        );
    }

    public CustomContractClause {
        Objects.requireNonNull(id);
        Objects.requireNonNull(effect);
        description = description == null ? "" : description;
        boundActorIds = copy(boundActorIds);
        actionTypes = actionTypes == null ? Set.of() : Set.copyOf(actionTypes);
        targetPlayerIds = copy(targetPlayerIds);
        relatedPlayerIds = copy(relatedPlayerIds);
        recipientPlayerIds = copy(recipientPlayerIds);
        topicKey = normalize(topicKey);
        requiredConsentFrom = copy(requiredConsentFrom);

        if (actionTypes.isEmpty()) {
            throw new IllegalArgumentException("custom clause requires at least one action type");
        }
    }

    public boolean matches(ContractActionRequest request) {
        if (request == null) return false;
        if (!boundActorIds.isEmpty() && !boundActorIds.contains(request.actorPlayerId())) return false;
        if (!actionTypes.contains(request.type())) return false;
        if (!matchesSelector(targetPlayerIds, request.targetPlayerId())) return false;
        if (!matchesSelector(relatedPlayerIds, request.relatedPlayerId())) return false;
        if (!matchesSelector(recipientPlayerIds, request.recipientPlayerId())) return false;

        String requestTopic = normalize(request.topicKey());
        if (topicKey != null && !Objects.equals(topicKey, requestTopic)) return false;

        return !request.consentPlayerIds().containsAll(requiredConsentFrom);
    }

    private static boolean matchesSelector(Set<String> selector, String value) {
        return selector.isEmpty() || (value != null && selector.contains(value));
    }

    private static Set<String> copy(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
