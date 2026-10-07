package com.wilddeck.engine;

import java.util.Objects;
import java.util.UUID;

public final class CombatEncounter {
    private final String id = UUID.randomUUID().toString();
    private final String attackerPlayerId;
    private final String targetPlayerId;
    private final String attackerCardId;
    private final String originalTargetCardId;
    private String defendingCardId;
    private CombatStatus status = CombatStatus.REACTION_WINDOW;
    private int exchanges;

    public CombatEncounter(
            String attackerPlayerId,
            String targetPlayerId,
            String attackerCardId,
            String targetCardId
    ) {
        this.attackerPlayerId = Objects.requireNonNull(attackerPlayerId);
        this.targetPlayerId = Objects.requireNonNull(targetPlayerId);
        this.attackerCardId = Objects.requireNonNull(attackerCardId);
        this.originalTargetCardId = Objects.requireNonNull(targetCardId);
        this.defendingCardId = targetCardId;
    }

    public String id() { return id; }
    public String attackerPlayerId() { return attackerPlayerId; }
    public String targetPlayerId() { return targetPlayerId; }
    public String attackerCardId() { return attackerCardId; }
    public String originalTargetCardId() { return originalTargetCardId; }
    public String defendingCardId() { return defendingCardId; }
    public CombatStatus status() { return status; }
    public int exchanges() { return exchanges; }

    public void interceptWith(String cardId) {
        requireOpen();
        defendingCardId = Objects.requireNonNull(cardId);
    }

    public void markResolved() { status = CombatStatus.RESOLVED; }
    public void markRetreated() { status = CombatStatus.RETREATED; }
    public void markCancelled() { status = CombatStatus.CANCELLED; }
    public void recordExchange() { exchanges++; }

    private void requireOpen() {
        if (status != CombatStatus.REACTION_WINDOW) {
            throw new IllegalStateException("combat encounter is not open");
        }
    }
}
