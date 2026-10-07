package com.wilddeck.engine;

public record CombatResult(
        String encounterId,
        String attackerCardId,
        String defenderCardId,
        int damageToAttacker,
        int damageToDefender,
        boolean attackerDied,
        boolean defenderDied,
        CombatStatus status
) {}
