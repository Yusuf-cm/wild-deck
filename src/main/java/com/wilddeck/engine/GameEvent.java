package com.wilddeck.engine;

import java.util.*;

public record GameEvent(
        String id,
        int round,
        String type,
        String actorPlayerId,
        String targetPlayerId,
        List<String> cardIds,
        String summary,
        Set<String> visibleToPlayerIds
) {
    public GameEvent(
            int round,
            String type,
            String actorPlayerId,
            String targetPlayerId,
            Collection<String> cardIds,
            String summary,
            Collection<String> visibleToPlayerIds
    ) {
        this(
                UUID.randomUUID().toString(),
                round,
                type,
                actorPlayerId,
                targetPlayerId,
                cardIds == null ? List.of() : List.copyOf(cardIds),
                summary == null ? "" : summary,
                visibleToPlayerIds == null ? Set.of() : Set.copyOf(visibleToPlayerIds)
        );
    }

    public boolean visibleTo(String playerId) {
        return visibleToPlayerIds.isEmpty() || visibleToPlayerIds.contains(playerId);
    }
}
