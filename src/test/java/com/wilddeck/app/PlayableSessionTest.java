package com.wilddeck.app;

import com.wilddeck.ai.*;
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
    @Test
    void reportsGroqSourceWhenStrategicProposalExecutes() {
        AiModelClient client = request -> """
                {
                  "candidates": [{
                    "kind": "DRAW",
                    "verb": null,
                    "source_card_ids": [],
                    "target_card_ids": [],
                    "target_player_id": null,
                    "card_id": null,
                    "hidden_play": false,
                    "message": "",
                    "strategic_summary": "Draw for more options.",
                    "confidence": 0.8
                  }],
                  "memory_update": {
                    "summary": "Opening turn.",
                    "trust_by_player": [],
                    "threat_by_player": [],
                    "suspicions": [],
                    "plans": []
                  }
                }
                """;
        GroqConfig config = new GroqConfig(
                "test-key","https://example.invalid","fast","strategic");
        WildDeckAiService ai = new WildDeckAiService(client,config);
        PlayableSession session = PlayableSession.standard(42,ai);

        assertTrue(session.endTurn().success());
        int handBefore = session.state().player("asha").hand().size();

        ActionExecutionResult result = session.runCurrentAiTurn();

        assertTrue(result.success());
        assertEquals(AiDecisionSource.GROQ,session.lastAiDecisionSource());
        assertTrue(session.lastAiDiagnostic().isBlank());
        assertEquals(handBefore + 1,session.state().player("asha").hand().size());
    }

    @Test
    void reportsFallbackWhenGroqThrowsInsteadOfSilentlyMaskingIt() {
        AiModelClient client = request -> {
            throw new IllegalStateException("simulated Groq outage");
        };
        GroqConfig config = new GroqConfig(
                "test-key","https://example.invalid","fast","strategic");
        WildDeckAiService ai = new WildDeckAiService(client,config);
        PlayableSession session = PlayableSession.standard(42,ai);

        assertTrue(session.endTurn().success());
        ActionExecutionResult result = session.runCurrentAiTurn();

        assertTrue(result.success());
        assertEquals(
                AiDecisionSource.HEURISTIC_AFTER_GROQ_ERROR,
                session.lastAiDecisionSource());
        assertTrue(session.lastAiDiagnostic().contains("simulated Groq outage"));
    }

}
