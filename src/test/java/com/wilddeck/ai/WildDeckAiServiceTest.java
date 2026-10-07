package com.wilddeck.ai;

import com.wilddeck.engine.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WildDeckAiServiceTest {

    @Test
    void redactedViewDoesNotLeakUnknownHiddenOpponentCard() {
        PlayerState self = new PlayerState("self","Self");
        PlayerState enemy = new PlayerState("enemy","Enemy");

        CardDefinition secretDef = new CardDefinition(
                "secret","Secret Dragon","Creature",
                Set.of("BIOLOGICAL","DRACONIC"),
                Set.of("ATTACK"),9,Map.of(),""
        );
        CardInstance secret = new CardInstance(
                secretDef,"enemy",Zone.KINGDOM,Visibility.HIDDEN);
        enemy.deploy(secret,true);

        GameState game = new GameState(
                GameRules.alphaV1(),List.of(self,enemy),List.of(),"self");

        String view = new AiGameViewBuilder().build(game,"self");

        assertFalse(view.contains("Secret Dragon"));
        assertFalse(view.contains(secret.id()));
        assertTrue(view.contains("\"hand\":\"HIDDEN\""));
    }

    @Test
    void opponentPlannerSkipsIllegalInventedActionAndChoosesLegalDraw() {
        PlayerState ai = new PlayerState("ai","AI");
        PlayerState enemy = new PlayerState("enemy","Enemy");

        CardDefinition drawDef = new CardDefinition(
                "draw","Roc","Creature",
                Set.of("BIOLOGICAL","FLYING"),
                Set.of("ATTACK"),7,Map.of(),""
        );
        CardInstance deckCard = new CardInstance(
                drawDef,"deck",Zone.DECK,Visibility.HIDDEN);

        GameState game = new GameState(
                GameRules.alphaV1(),List.of(ai,enemy),List.of(deckCard),"ai");

        String fakePlan = """
        {
          "candidates":[
            {
              "kind":"WORLD_ACTION",
              "verb":"ATTACK",
              "source_card_ids":["invented-card"],
              "target_card_ids":[],
              "target_player_id":"enemy",
              "card_id":null,
              "hidden_play":false,
              "message":"",
              "strategic_summary":"Invented attack should fail validation.",
              "confidence":0.9
            },
            {
              "kind":"DRAW",
              "verb":null,
              "source_card_ids":[],
              "target_card_ids":[],
              "target_player_id":null,
              "card_id":null,
              "hidden_play":false,
              "message":"",
              "strategic_summary":"Draw for options.",
              "confidence":0.8
            }
          ]
        }
        """;

        WildDeckAiService service = serviceReturning(fakePlan);
        AiActionProposal chosen = service.chooseOpponentAction(game,"ai");

        assertEquals(AiActionKind.DRAW,chosen.kind());
    }

    @Test
    void aiContractTranslationCreatesEnforceableSilenceClause() {
        String translated = """
        {
          "clauses":[
            {
              "description":"Michelle may not discuss Yusuf and Brian's relationship without their consent.",
              "bound_actor_ids":["michelle"],
              "action_types":["DISCUSS_INFORMATION","DISCLOSE_INFORMATION"],
              "target_player_ids":[],
              "related_player_ids":[],
              "recipient_player_ids":[],
              "topic_key":"RELATIONSHIP:YUSUF:BRIAN",
              "required_consent_from":["yusuf","brian"]
            }
          ]
        }
        """;

        WildDeckAiService service = serviceReturning(translated);
        List<CustomContractClause> clauses = service.translateCustomContractClauses(
                List.of("yusuf","brian","michelle"),
                "Michelle cannot discuss Yusuf and Brian unless both consent."
        );

        assertEquals(1,clauses.size());
        CustomContractClause clause = clauses.get(0);
        assertEquals("RELATIONSHIP:YUSUF:BRIAN",clause.topicKey());
        assertTrue(clause.actionTypes().contains(ContractActionType.DISCUSS_INFORMATION));
        assertEquals(Set.of("yusuf","brian"),clause.requiredConsentFrom());
    }

    @Test
    void aiClassifiesContractRelevantInformationAction() {
        PlayerState michelle = new PlayerState("michelle","Michelle");
        PlayerState brian = new PlayerState("brian","Brian");
        PlayerState yusuf = new PlayerState("yusuf","Yusuf");
        GameState game = new GameState(
                GameRules.alphaV1(),List.of(michelle,brian,yusuf),List.of(),"michelle");

        String classification = """
        {
          "action_type":"DISCUSS_INFORMATION",
          "target_player_id":null,
          "related_player_id":"brian",
          "contract_id":null,
          "topic_key":"RELATIONSHIP:YUSUF:BRIAN",
          "recipient_player_id":"brian",
          "consent_player_ids":[]
        }
        """;

        WildDeckAiService service = serviceReturning(classification);
        ContractActionRequest request = service.classifyContractAction(
                game,"michelle","Tell Brian what I know about his secret relationship with Yusuf.");

        assertEquals(ContractActionType.DISCUSS_INFORMATION,request.type());
        assertEquals("RELATIONSHIP:YUSUF:BRIAN",request.topicKey());
    }

    private static WildDeckAiService serviceReturning(String json) {
        AiModelClient fake = request -> json;
        GroqConfig config = new GroqConfig(
                "test-key",
                "https://example.invalid",
                "openai/gpt-oss-20b",
                "openai/gpt-oss-120b"
        );
        return new WildDeckAiService(fake,config);
    }
}
