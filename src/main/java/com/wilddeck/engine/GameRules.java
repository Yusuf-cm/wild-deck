package com.wilddeck.engine;

public record GameRules(
        int baseWealthIncomePerRound,
        int startingGold,
        int startingWealth,
        int startingMana
) {
    public GameRules {
        if (baseWealthIncomePerRound < 0 || startingGold < 0 || startingWealth < 0 || startingMana < 0) {
            throw new IllegalArgumentException("resources cannot be negative");
        }
    }

    public static GameRules alphaV1() {
        return new GameRules(1, 0, 0, 0);
    }
}
