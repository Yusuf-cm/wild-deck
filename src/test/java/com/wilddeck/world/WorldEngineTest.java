package com.wilddeck.world;

import com.wilddeck.engine.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldEngineTest {
    @Test void deploymentAndMovementRespectWorldConnectionsAndRoundLimit() {
        PlayerState alice = new PlayerState("alice", "Alice");
        PlayerState bob = new PlayerState("bob", "Bob");
        CardDefinition definition = new CardDefinition("unit", "Scout", "UNIT",
                Set.of("HUMAN"), Set.of("MOVE"), 2, Map.of(), "");
        CardInstance scout = new CardInstance(definition, "alice", Zone.HAND, Visibility.HIDDEN);
        alice.addToHand(scout);
        GameState state = new GameState(GameRules.alphaV1(), List.of(alice, bob),
                List.of(), "alice");
        state.setWorld(new WorldGenerator().generate(42, List.of("alice", "bob")));
        new GameEngine().playFromHand(state, "alice", scout.id(), false);
        assertEquals("kingdom:alice", state.cardLocation(scout.id()).orElseThrow());

        String neighbor = state.world().neighbors("kingdom:alice").iterator().next();
        WorldEngine world = new WorldEngine();
        world.move(state, "alice", scout.id(), neighbor);
        assertEquals(neighbor, state.cardLocation(scout.id()).orElseThrow());
        assertThrows(IllegalStateException.class,
                () -> world.move(state, "alice", scout.id(), "kingdom:alice"));
        state.nextRound();
        world.move(state, "alice", scout.id(), "kingdom:alice");
        assertEquals("kingdom:alice", state.cardLocation(scout.id()).orElseThrow());
    }

    @Test void enemyCannotMoveOtherPlayersCard() {
        PlayerState alice = new PlayerState("alice", "Alice");
        PlayerState bob = new PlayerState("bob", "Bob");
        CardDefinition definition = new CardDefinition("unit", "Scout", "UNIT",
                Set.of(), Set.of("MOVE"), 2, Map.of(), "");
        CardInstance scout = new CardInstance(definition, "alice", Zone.HAND, Visibility.HIDDEN);
        alice.addToHand(scout);
        GameState state = new GameState(GameRules.alphaV1(), List.of(alice, bob),
                List.of(), "alice");
        state.setWorld(new WorldGenerator().generate(42, List.of("alice", "bob")));
        new GameEngine().playFromHand(state, "alice", scout.id(), false);
        assertThrows(IllegalStateException.class,
                () -> new WorldEngine().move(state, "bob", scout.id(), "forest"));
    }
}
