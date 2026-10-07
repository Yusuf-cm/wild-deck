package com.wilddeck.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wilddeck.engine.*;

import java.util.*;

/**
 * Builds a player-specific AI view.
 *
 * The AI sees exactly what the selected player is allowed to know:
 * - its own hand, resources and Kingdom
 * - opponent cards only when known to that player
 * - contracts only when the player is a participant
 * - access routes owned by the player or publicly visible
 *
 * Opponent hands and hidden unknown assets are never serialized.
 */
public final class AiGameViewBuilder {
    private final ObjectMapper mapper = new ObjectMapper();

    public String build(GameState state, String viewerPlayerId) {
        PlayerState viewer = state.player(viewerPlayerId);

        Map<String,Object> root = new LinkedHashMap<>();
        root.put("round", state.round());
        root.put("active_player_id", state.activePlayerId());
        root.put("main_action_used", state.mainActionUsed());
        root.put("deck_cards_remaining", state.deckSize());

        root.put("self", playerSelfView(viewer));
        root.put("opponents", opponentViews(state, viewer));
        root.put("contracts", visibleContracts(state, viewerPlayerId));
        root.put("contract_obligations", visibleObligations(state, viewerPlayerId));
        root.put("access_routes", visibleRoutes(state, viewerPlayerId));
        root.put("relationships", visibleRelationships(state, viewer));

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize AI game view", e);
        }
    }

    private Map<String,Object> playerSelfView(PlayerState player) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", player.id());
        out.put("name", player.name());
        out.put("kingdom_status", player.kingdomStatus().name());
        out.put("overlord_id", player.overlordId().orElse(null));
        out.put("resources", player.resources().snapshot());
        out.put("hand", player.hand().stream().map(this::cardView).toList());
        out.put("kingdom", player.kingdom().stream().map(this::cardView).toList());
        out.put("graveyard", player.graveyard().stream().map(this::cardView).toList());
        return out;
    }

    private List<Map<String,Object>> opponentViews(GameState state, PlayerState viewer) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (PlayerState opponent : state.players()) {
            if (opponent.id().equals(viewer.id())) continue;

            Map<String,Object> item = new LinkedHashMap<>();
            item.put("id", opponent.id());
            item.put("name", opponent.name());
            item.put("kingdom_status", opponent.kingdomStatus().name());
            item.put("overlord_id", opponent.overlordId().orElse(null));

            List<Map<String,Object>> knownCards = opponent.kingdom().stream()
                    .filter(card -> card.visibility() == Visibility.PUBLIC || viewer.knows(card.id()))
                    .map(this::cardView)
                    .toList();

            List<Map<String,Object>> knownGraveyard = opponent.graveyard().stream()
                    .filter(card -> viewer.knows(card.id()))
                    .map(this::cardView)
                    .toList();

            item.put("known_kingdom_cards", knownCards);
            item.put("known_graveyard_cards", knownGraveyard);
            item.put("hand", "HIDDEN");
            item.put("resources", "UNKNOWN");
            out.add(item);
        }
        return List.copyOf(out);
    }

    private List<Map<String,Object>> visibleContracts(GameState state, String viewerId) {
        return state.contracts().stream()
                .filter(c -> c.participantIds().contains(viewerId))
                .map(c -> {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("id", c.id());
                    item.put("participants", c.participantIds());
                    item.put("terms", c.terms());
                    item.put("custom_clauses", c.customClauses());
                    item.put("start_round", c.startRound());
                    item.put("end_round", c.endRound());
                    return item;
                })
                .toList();
    }

    private List<Map<String,Object>> visibleObligations(GameState state, String viewerId) {
        return state.contractObligations().stream()
                .filter(o -> o.obligatedPlayerId().equals(viewerId)
                        || o.protectedPlayerId().equals(viewerId))
                .map(o -> {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("id", o.id());
                    item.put("contract_id", o.contractId());
                    item.put("term", o.term().name());
                    item.put("obligated_player_id", o.obligatedPlayerId());
                    item.put("protected_player_id", o.protectedPlayerId());
                    item.put("due_round", o.dueRound());
                    item.put("status", o.status().name());
                    return item;
                })
                .toList();
    }

    private List<Map<String,Object>> visibleRoutes(GameState state, String viewerId) {
        return state.accessRoutes().stream()
                .filter(r -> r.ownerPlayerId().equals(viewerId) || r.visibility() == Visibility.PUBLIC)
                .map(r -> {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("id", r.id());
                    item.put("owner_player_id", r.ownerPlayerId());
                    item.put("from_player_id", r.fromPlayerId());
                    item.put("to_player_id", r.toPlayerId());
                    item.put("type", r.type().name());
                    item.put("support_card_ids", r.supportCardIds());
                    item.put("active", r.active());
                    return item;
                })
                .toList();
    }

    private List<Map<String,Object>> visibleRelationships(GameState state, PlayerState viewer) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (CardRelation relation : state.cardRelations()) {
            boolean knowsSource = viewer.knows(relation.sourceCardId())
                    || state.findCard(relation.sourceCardId())
                    .map(c -> c.visibility() == Visibility.PUBLIC)
                    .orElse(false);
            boolean knowsTarget = viewer.knows(relation.targetCardId())
                    || state.findCard(relation.targetCardId())
                    .map(c -> c.visibility() == Visibility.PUBLIC)
                    .orElse(false);

            if (knowsSource && knowsTarget) {
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("id", relation.id());
                item.put("type", relation.type().name());
                item.put("source_card_id", relation.sourceCardId());
                item.put("target_card_id", relation.targetCardId());
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private Map<String,Object> cardView(CardInstance card) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("instance_id", card.id());
        out.put("name", card.definition().name());
        out.put("category", card.definition().category());
        out.put("properties", card.definition().properties());
        out.put("capabilities", card.definition().capabilities());
        out.put("strength", card.definition().strength());
        out.put("states", card.states());
        out.put("damage", card.damage());
        out.put("zone", card.zone().name());
        out.put("visibility", card.visibility().name());
        return out;
    }
}
