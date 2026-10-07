package com.wilddeck.engine;

public final class GameEngine {

    public void initializeResources(GameState state) {
        for (PlayerState player : state.players()) {
            player.resources().add(ResourceType.GOLD, state.rules().startingGold());
            player.resources().add(ResourceType.WEALTH, state.rules().startingWealth());
            player.resources().add(ResourceType.MANA, state.rules().startingMana());
        }
    }

    public void startRound(GameState state, String firstPlayerId) {
        for (PlayerState player : state.players()) {
            player.resources().add(ResourceType.WEALTH, state.rules().baseWealthIncomePerRound());
        }
        state.beginTurn(firstPlayerId);
    }

    public void advanceRound(GameState state, String firstPlayerId) {
        state.nextRound();
        new ContractEngine().auditOverdue(state);
        startRound(state, firstPlayerId);
    }

    public CardInstance draw(GameState state, String playerId) {
        requireActive(state, playerId);
        if (state.mainActionUsed()) throw new IllegalStateException("main action already used");
        CardInstance card = state.drawTop();
        state.player(playerId).addToHand(card);
        state.consumeMainAction();
        return card;
    }

    public CardInstance playFromHand(GameState state, String playerId, String cardId, boolean hidden) {
        requireActive(state, playerId);
        if (state.mainActionUsed()) throw new IllegalStateException("main action already used");

        PlayerState player = state.player(playerId);
        CardInstance card = player.removeFromHand(cardId);
        if (card == null) throw new IllegalArgumentException("card is not in hand");

        if (!player.resources().canAfford(card.definition().cost())) {
            player.addToHand(card);
            throw new IllegalStateException("cannot afford card");
        }

        player.resources().spend(card.definition().cost());
        player.deploy(card, hidden);

        if (!hidden) {
            for (PlayerState observer : state.players()) observer.reveal(card.id());
        }

        state.consumeMainAction();
        return card;
    }

    public void addContract(GameState state, Contract contract) {
        new ContractEngine().registerContract(state, contract);
    }

    private void requireActive(GameState state, String playerId) {
        if (!state.activePlayerId().equals(playerId))
            throw new IllegalStateException("not this player's turn");
    }
}
