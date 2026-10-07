package com.wilddeck.app;

import com.wilddeck.engine.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PlayableSessionTest {

    @Test
    void alphaDeckIsDeterministicForSeed() {
        AlphaDeckFactory factory = new AlphaDeckFactory();
        List<CardInstance> a = factory.create(123);
        List<CardInstance> b = factory.create(123);

        assertEquals(
                a.stream().limit(12).map(c -> c.definition().name()).toList(),
                b.stream().limit(12).map(c -> c.definition().name()).toList()
        );
        assertTrue(a.size() >= 60);
    }

    @Test
    void standardSessionDealsSevenCardsAndStartsWithWealth() {
        PlayableSession session = PlayableSession.standard(42,null);
        GameState state = session.state();

        for (PlayerState player : state.players()) {
            assertEquals(7,player.hand().size());
            assertEquals(1,player.resources().get(ResourceType.WEALTH));
        }

        assertEquals("player",state.activePlayerId());
        assertEquals(1,state.round());
    }

    @Test
    void fullTurnCycleAdvancesRound() {
        PlayableSession session = PlayableSession.standard(42,null);

        assertTrue(session.endTurn().success());
        assertEquals("asha",session.currentPlayerId());

        assertTrue(session.endTurn().success());
        assertEquals("brian",session.currentPlayerId());

        assertTrue(session.endTurn().success());
        assertEquals("player",session.currentPlayerId());
        assertEquals(2,session.state().round());
    }

    @Test
    void hiddenDeploymentEventDoesNotLeakToOpponent() {
        PlayableSession session = PlayableSession.standard(42,null);
        PlayerState human = session.state().player("player");
        CardInstance card = human.hand().stream()
                .filter(c -> human.resources().canAfford(c.definition().cost()))
                .findFirst().orElseThrow();

        ActionExecutionResult result = session.executor().play(
                session.state(),"player",card.id(),true);

        assertTrue(result.success());

        String privateSummary = session.state().visibleEvents("player",20).stream()
                .map(GameEvent::summary)
                .filter(s -> s.contains(card.definition().name()))
                .findFirst().orElse(null);

        String leaked = session.state().visibleEvents("asha",20).stream()
                .map(GameEvent::summary)
                .filter(s -> s.contains(card.definition().name()))
                .findFirst().orElse(null);

        assertNotNull(privateSummary);
        assertNull(leaked);
    }
}
