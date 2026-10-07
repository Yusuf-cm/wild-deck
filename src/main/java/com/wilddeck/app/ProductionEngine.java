package com.wilddeck.app;

import com.wilddeck.engine.*;

public final class ProductionEngine {
    public void collectForRound(GameState state) {
        for (PlayerState player : state.players()) {
            if (player.isEliminated()) continue;
            for (CardInstance card : player.kingdom()) {
                if (card.isDead()) continue;
                if (card.definition().hasCapability("PRODUCE GOLD")) {
                    player.resources().add(ResourceType.GOLD,1);
                }
                if (card.definition().hasCapability("PRODUCE MANA")) {
                    player.resources().add(ResourceType.MANA,1);
                }
            }
        }
    }
}
