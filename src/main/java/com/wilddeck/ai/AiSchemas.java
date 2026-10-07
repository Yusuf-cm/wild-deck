package com.wilddeck.ai;

import com.fasterxml.jackson.databind.*;

public final class AiSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiSchemas() {}

    public static JsonNode actionProposal() {
        return parse("""
        {
          "type":"object",
          "properties":{
            "kind":{"type":"string","enum":["DRAW","PLAY","WORLD_ACTION","NEGOTIATE","PASS"]},
            "verb":{"type":["string","null"]},
            "source_card_ids":{"type":"array","items":{"type":"string"}},
            "target_card_ids":{"type":"array","items":{"type":"string"}},
            "target_player_id":{"type":["string","null"]},
            "card_id":{"type":["string","null"]},
            "hidden_play":{"type":"boolean"},
            "message":{"type":"string"},
            "strategic_summary":{"type":"string"},
            "confidence":{"type":"number"}
          },
          "required":["kind","verb","source_card_ids","target_card_ids","target_player_id","card_id","hidden_play","message","strategic_summary","confidence"],
          "additionalProperties":false
        }
        """);
    }

    public static JsonNode plan() {
        JsonNode action = actionProposal();
        try {
            var root = MAPPER.createObjectNode();
            root.put("type","object");
            var props = root.putObject("properties");
            var candidates = props.putObject("candidates");
            candidates.put("type","array");
            candidates.put("minItems",1);
            candidates.put("maxItems",5);
            candidates.set("items",action);
            root.putArray("required").add("candidates");
            root.put("additionalProperties",false);
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode contractTranslation() {
        return parse("""
        {
          "type":"object",
          "properties":{
            "clauses":{
              "type":"array",
              "items":{
                "type":"object",
                "properties":{
                  "description":{"type":"string"},
                  "bound_actor_ids":{"type":"array","items":{"type":"string"}},
                  "action_types":{"type":"array","items":{"type":"string","enum":[
                    "HOSTILE_DIRECT","HOSTILE_PROXY","HOSTILE_COOPERATION","DISCLOSE_ALLIANCE",
                    "INVESTIGATE_INFORMATION","SPY_INFORMATION","QUESTION_OTHERS",
                    "DISCLOSE_INFORMATION","TRADE_INFORMATION","SPREAD_INFORMATION","DISCUSS_INFORMATION"
                  ]}},
                  "target_player_ids":{"type":"array","items":{"type":"string"}},
                  "related_player_ids":{"type":"array","items":{"type":"string"}},
                  "recipient_player_ids":{"type":"array","items":{"type":"string"}},
                  "topic_key":{"type":["string","null"]},
                  "required_consent_from":{"type":"array","items":{"type":"string"}}
                },
                "required":[
                  "description","bound_actor_ids","action_types","target_player_ids",
                  "related_player_ids","recipient_player_ids","topic_key","required_consent_from"
                ],
                "additionalProperties":false
              }
            }
          },
          "required":["clauses"],
          "additionalProperties":false
        }
        """);
    }

    public static JsonNode contractAction() {
        return parse("""
        {
          "type":"object",
          "properties":{
            "action_type":{"type":"string","enum":[
              "HOSTILE_DIRECT","HOSTILE_PROXY","HOSTILE_COOPERATION","DISCLOSE_ALLIANCE",
              "DEFENSIVE_SUPPORT","SHARE_CONFIRMED_THREAT",
              "INVESTIGATE_INFORMATION","SPY_INFORMATION","QUESTION_OTHERS",
              "DISCLOSE_INFORMATION","TRADE_INFORMATION","SPREAD_INFORMATION","DISCUSS_INFORMATION"
            ]},
            "target_player_id":{"type":["string","null"]},
            "related_player_id":{"type":["string","null"]},
            "contract_id":{"type":["string","null"]},
            "topic_key":{"type":["string","null"]},
            "recipient_player_id":{"type":["string","null"]},
            "consent_player_ids":{"type":"array","items":{"type":"string"}}
          },
          "required":[
            "action_type","target_player_id","related_player_id","contract_id",
            "topic_key","recipient_player_id","consent_player_ids"
          ],
          "additionalProperties":false
        }
        """);
    }

    private static JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("invalid built-in AI schema",e);
        }
    }
}
