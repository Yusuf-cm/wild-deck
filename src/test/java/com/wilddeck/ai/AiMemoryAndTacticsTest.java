package com.wilddeck.ai;

import com.wilddeck.engine.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AiMemoryAndTacticsTest {

    @Test
    void strategicPlanningPersistsMemoryFromSameModelCall() {
        PlayerState ai = new PlayerState("ai","AI");
        PlayerState enemy = new PlayerState("enemy","Enemy");

        CardDefinition drawDef = new CardDefinition(
                "roc","Roc","Creature",Set.of("BIOLOGICAL","FLYING"),
                Set.of("ATTACK"),7,Map.of(),"");
        GameState game = new GameState(
                GameRules.alphaV1(),List.of(ai,enemy),
                List.of(new CardInstance(drawDef,"deck",Zone.DECK,Visibility.HIDDEN)),"ai");

        String response = """
        {
          "candidates":[{
            "kind":"DRAW",
            "verb":null,
            "source_card_ids":[],
            "target_card_ids":[],
            "target_player_id":null,
            "card_id":null,
            "hidden_play":false,
            "message":"",
            "strategic_summary":"Need more options.",
            "confidence":0.8
          }],
          "memory_update":{
            "summary":"Enemy has shown no military yet.",
            "trust_by_player":[{"player_id":"enemy","score":0.3}],
            "threat_by_player":[{"player_id":"enemy","score":0.4}],
            "suspicions":["Enemy may be holding combat cards."],
            "plans":["Develop before committing."]
          }
        }
        """;

        AiModelClient fake = request -> response;
        GroqConfig cfg = new GroqConfig("test","x","fast","strategic");
        AiMemoryStore memory = new AiMemoryStore();
        WildDeckAiService service = new WildDeckAiService(
                fake,cfg,memory,new TacticalAnalyzer());

        assertEquals(AiActionKind.DRAW,service.chooseOpponentAction(game,"ai").kind());
        assertEquals("Enemy has shown no military yet.",memory.get("ai").summary());
        assertEquals(0.4,memory.get("ai").threatByPlayer().get("enemy"));
    }

    @Test
    void tacticalAnalyzerDoesNotListUnknownHiddenTarget() {
        PlayerState ai = new PlayerState("ai","AI");
        PlayerState enemy = new PlayerState("enemy","Enemy");

        CardInstance attacker = deployed(ai,new CardDefinition(
                "hydra","Hydra","Creature",
                Set.of("BIOLOGICAL"),Set.of("ATTACK"),8,Map.of(),""));

        CardInstance hidden = new CardInstance(
                new CardDefinition(
                        "secret","Secret Guard","Military",
                        Set.of("HUMAN"),Set.of("ATTACK","DEFEND"),5,Map.of(),""),
                "enemy",Zone.KINGDOM,Visibility.HIDDEN);
        enemy.deploy(hidden,true);

        CardInstance visible = new CardInstance(
                new CardDefinition(
                        "visible","Public Militia","Military",
                        Set.of("HUMAN","ARMY"),Set.of("ATTACK","DEFEND"),4,Map.of(),""),
                "enemy",Zone.KINGDOM,Visibility.PUBLIC);
        enemy.deploy(visible,false);

        GameState game = new GameState(
                GameRules.alphaV1(),List.of(ai,enemy),List.of(),"ai");

        String tactical = new TacticalAnalyzer().analyze(game,"ai");

        assertTrue(tactical.contains(attacker.id()));
        assertTrue(tactical.contains(visible.id()));
        assertTrue(tactical.contains("Public Militia"));
        assertFalse(tactical.contains(hidden.id()));
        assertFalse(tactical.contains("Secret Guard"));
    }

    private static CardInstance deployed(PlayerState p,CardDefinition def) {
        CardInstance c = new CardInstance(def,p.id(),Zone.KINGDOM,Visibility.PUBLIC);
        p.deploy(c,false);
        return c;
    }
}
