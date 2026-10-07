package com.wilddeck.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wilddeck.engine.*;

import java.util.*;

/**
 * Cheap deterministic tactical rollouts used to ground the strategic model.
 * These are not future-state mutations: they estimate one immediate exchange.
 */
public final class TacticalAnalyzer {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WarfareRules rules = WarfareRules.alphaV1();
    private final AccessEngine access = new AccessEngine();

    public String analyze(GameState state, String playerId) {
        PlayerState player = state.player(playerId);
        List<Map<String,Object>> attacks = new ArrayList<>();

        for (CardInstance attacker : player.kingdom()) {
            if (!attacker.isCombatCapable()
                    || attacker.definition().strength() == null
                    || !attacker.definition().hasCapability("ATTACK")) {
                continue;
            }

            for (PlayerState opponent : state.players()) {
                if (opponent.id().equals(playerId) || opponent.isEliminated()) continue;

                for (CardInstance target : opponent.kingdom()) {
                    if (target.visibility() != Visibility.PUBLIC && !player.knows(target.id())) continue;
                    AccessDecision reach = access.canReach(state,playerId,attacker.id(),target.id());
                    if (!reach.allowed()) continue;

                    Map<String,Object> option = new LinkedHashMap<>();
                    option.put("attacker_id",attacker.id());
                    option.put("attacker_name",attacker.definition().name());
                    option.put("target_id",target.id());
                    option.put("target_name",target.definition().name());
                    option.put("target_player_id",opponent.id());

                    int dealt = rules.strikeDamage(attacker.definition().strength());
                    int retaliation = 0;
                    if (target.definition().strength() != null
                            && (target.definition().hasCapability("ATTACK")
                            || target.definition().hasCapability("DEFEND")
                            || target.definition().hasCapability("GUARD"))) {
                        retaliation = rules.strikeDamage(target.definition().strength());
                    }

                    option.put("estimated_damage_dealt",dealt);
                    option.put("estimated_retaliation",retaliation);
                    option.put("target_would_die",
                            target.damage() + dealt >= target.vitality());
                    option.put("attacker_would_die",
                            attacker.damage() + retaliation >= attacker.vitality());
                    option.put("access_basis",reach.reason());
                    attacks.add(option);
                }
            }
        }

        Map<String,Object> root = new LinkedHashMap<>();
        root.put("legal_immediate_attacks",attacks);
        root.put("open_contract_obligations",
                state.contractObligations().stream()
                        .filter(o -> o.obligatedPlayerId().equals(playerId)
                                && o.status() == ContractObligationStatus.OPEN)
                        .map(o -> Map.of(
                                "id",o.id(),
                                "term",o.term().name(),
                                "protected_player_id",o.protectedPlayerId(),
                                "due_round",o.dueRound()
                        ))
                        .toList());
        root.put("hand_size",player.hand().size());
        root.put("kingdom_size",player.kingdom().size());
        root.put("resources",player.resources().snapshot());

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize tactical analysis",e);
        }
    }
}
