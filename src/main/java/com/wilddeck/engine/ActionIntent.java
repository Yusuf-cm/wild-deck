package com.wilddeck.engine;

import java.util.*;

public record ActionIntent(
        String actingPlayerId,
        String verb,
        List<String> sourceCardIds,
        List<String> targetCardIds,
        String targetPlayerId
) {
    public ActionIntent {
        sourceCardIds = sourceCardIds == null ? List.of() : List.copyOf(sourceCardIds);
        targetCardIds = targetCardIds == null ? List.of() : List.copyOf(targetCardIds);
    }

    public String normalizedVerb() { return verb.trim().toUpperCase(); }
}
