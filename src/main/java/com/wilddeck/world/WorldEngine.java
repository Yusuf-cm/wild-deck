package com.wilddeck.world;

import com.wilddeck.engine.*;
import java.util.*;

/** Authoritative placement and movement rules for the location graph. */
public final class WorldEngine {
    public void deployAtHome(GameState state, CardInstance card) {
        if (card.zone() != Zone.KINGDOM) throw new IllegalStateException("card is not deployed");
        state.placeCard(card.id(), "kingdom:" + card.controllerId());
    }

    public void move(GameState state, String actorId, String cardId, String destinationId) {
        if (!actorId.equals(state.activePlayerId())) throw new IllegalStateException("not your turn");
        CardInstance card = state.findCard(cardId)
                .orElseThrow(() -> new IllegalArgumentException("unknown card"));
        if (!actorId.equals(card.controllerId())) throw new IllegalStateException("not your card");
        if (card.zone() != Zone.KINGDOM || card.isDead() || !card.isCombatCapable())
            throw new IllegalStateException("card cannot move while inactive");
        boolean mobile = List.of("MOVE", "ATTACK", "OCCUPY", "FLY", "BURROW", "INFILTRATE")
                .stream().anyMatch(card.definition()::hasCapability);
        if (!mobile) throw new IllegalStateException("card has no movement capability");
        String from = state.cardLocation(cardId)
                .orElseThrow(() -> new IllegalStateException("card has no world location"));
        if (!state.world().connected(from, destinationId))
            throw new IllegalStateException("destination is not adjacent");
        if (!state.markCardMovedThisRound(cardId))
            throw new IllegalStateException("card already moved this round");
        state.placeCard(cardId, destinationId);
        state.addEvent(new GameEvent(state.round(), "WORLD_MOVE", actorId, null,
                List.of(cardId), card.definition().name() + " moved to "
                        + state.world().location(destinationId).orElseThrow().name() + ".",
                card.visibility() == Visibility.PUBLIC ? Set.of() : Set.of(actorId)));
    }
}
