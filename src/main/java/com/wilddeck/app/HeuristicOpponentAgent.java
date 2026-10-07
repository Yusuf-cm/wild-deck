package com.wilddeck.app;

import com.wilddeck.ai.*;
import com.wilddeck.engine.*;

import java.util.*;

/**
 * Deterministic offline fallback so the game is playable and testable without
 * external inference. Groq remains the preferred opponent when configured.
 */
public final class HeuristicOpponentAgent {

    public AiActionProposal choose(GameState state,String playerId) {
        PlayerState player = state.player(playerId);

        AiActionProposal attack = bestAttack(state,player);
        if (attack != null) return attack;

        if (!state.mainActionUsed()) {
            Optional<CardInstance> bestPlayable = player.hand().stream()
                    .filter(c -> player.resources().canAfford(c.definition().cost()))
                    .max(Comparator.comparingInt(this::playValue));
            if (bestPlayable.isPresent()) {
                CardInstance card = bestPlayable.get();
                boolean hidden = card.definition().hasProperty("SPECIALIST")
                        || card.definition().hasProperty("COVERT");
                return new AiActionProposal(
                        AiActionKind.PLAY,null,List.of(),List.of(),null,card.id(),hidden,"",
                        "Deploy the strongest useful available card.",0.7);
            }

            if (state.deckSize() > 0) {
                return new AiActionProposal(
                        AiActionKind.DRAW,null,List.of(),List.of(),null,null,false,"",
                        "No useful affordable deployment; draw.",0.8);
            }
        }

        return new AiActionProposal(
                AiActionKind.PASS,null,List.of(),List.of(),null,null,false,"",
                "No useful legal action.",1.0);
    }

    private AiActionProposal bestAttack(GameState state,PlayerState player) {
        AiActionProposal best = null;
        int bestScore = Integer.MIN_VALUE;
        AccessEngine access = new AccessEngine();
        WarfareRules rules = WarfareRules.alphaV1();

        for (CardInstance attacker : player.kingdom()) {
            if (!attacker.isCombatCapable()
                    || attacker.definition().strength() == null
                    || !attacker.definition().hasCapability("ATTACK")) continue;

            for (PlayerState opponent : state.players()) {
                if (opponent.id().equals(player.id()) || opponent.isEliminated()) continue;
                if (state.blocksHostility(player.id(),opponent.id())) continue;

                for (CardInstance target : opponent.kingdom()) {
                    if (target.visibility() != Visibility.PUBLIC && !player.knows(target.id())) continue;
                    AccessDecision reach = access.canReach(state,player.id(),attacker.id(),target.id());
                    if (!reach.allowed()) continue;

                    int dealt = rules.strikeDamage(attacker.definition().strength());
                    int retaliation = target.definition().strength() == null ? 0
                            : rules.strikeDamage(target.definition().strength());
                    boolean kill = target.damage() + dealt >= target.vitality();
                    boolean die = attacker.damage() + retaliation >= attacker.vitality();

                    int score = dealt - retaliation + (kill ? 8 : 0) - (die ? 10 : 0);
                    if (score > bestScore && score >= 1) {
                        bestScore = score;
                        best = new AiActionProposal(
                                AiActionKind.WORLD_ACTION,"ATTACK",
                                List.of(attacker.id()),List.of(target.id()),opponent.id(),null,
                                false,"","Favorable immediate attack.",0.7);
                    }
                }
            }
        }
        return best;
    }

    private int playValue(CardInstance card) {
        int value = card.definition().strength() == null ? 1 : card.definition().strength();
        if (card.definition().hasCapability("GUARD")) value += 2;
        if (card.definition().hasCapability("OCCUPY")) value += 2;
        if (card.definition().hasCapability("PRODUCE GOLD")
                || card.definition().hasCapability("PRODUCE MANA")) value += 3;
        return value;
    }
}
