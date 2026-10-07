package com.wilddeck.engine;

import java.util.Map;

public record KingdomWarfareResult(
        String actingPlayerId,
        String targetPlayerId,
        KingdomStatus targetStatus,
        int transferredCards,
        int destroyedCards,
        Map<ResourceType,Integer> transferredResources
) {
    public KingdomWarfareResult {
        transferredResources = transferredResources == null ? Map.of() : Map.copyOf(transferredResources);
    }
}
