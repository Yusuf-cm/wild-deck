package com.wilddeck.engine;

import java.util.Objects;
import java.util.UUID;

public record CardRelation(
        String id,
        CardRelationType type,
        String sourceCardId,
        String targetCardId
) {
    public CardRelation(CardRelationType type, String sourceCardId, String targetCardId) {
        this(UUID.randomUUID().toString(), type, sourceCardId, targetCardId);
    }

    public CardRelation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(sourceCardId);
        Objects.requireNonNull(targetCardId);
        if (sourceCardId.equals(targetCardId)) {
            throw new IllegalArgumentException("a card cannot relate to itself");
        }
    }
}
