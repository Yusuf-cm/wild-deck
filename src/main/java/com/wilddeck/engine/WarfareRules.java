package com.wilddeck.engine;

/**
 * Tuning values for deterministic alpha combat.
 *
 * Strength currently doubles as a unit's combat power and vitality ceiling.
 * This is intentionally isolated so later balance changes do not require
 * rewriting the rest of the warfare engine.
 */
public record WarfareRules(
        int damageDivisor,
        int regenerationPerTurn,
        int standardHealingAmount
) {
    public WarfareRules {
        if (damageDivisor < 1 || regenerationPerTurn < 0 || standardHealingAmount < 1) {
            throw new IllegalArgumentException("invalid warfare rules");
        }
    }

    public static WarfareRules alphaV1() {
        return new WarfareRules(3, 1, 2);
    }

    public int strikeDamage(int strength) {
        return Math.max(1, (int)Math.ceil(strength / (double) damageDivisor));
    }
}
