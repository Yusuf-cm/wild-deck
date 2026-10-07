package com.wilddeck.engine;

import java.util.*;

/**
 * Authoritative deterministic warfare layer.
 *
 * Alpha v1 intentionally keeps arithmetic simple. Card physics, reactions,
 * access and future status effects can modify this later without changing the
 * combat lifecycle.
 */
public final class WarfareEngine {
    private final WarfareRules rules;
    private final InteractionEngine interactions;

    public WarfareEngine() {
        this(WarfareRules.alphaV1(), new InteractionEngine());
    }

    public WarfareEngine(WarfareRules rules, InteractionEngine interactions) {
        this.rules = Objects.requireNonNull(rules);
        this.interactions = Objects.requireNonNull(interactions);
    }

    public CombatEncounter declareAttack(
            GameState state,
            String attackerPlayerId,
            String attackerCardId,
            String targetCardId
    ) {
        CardInstance attacker = requireCard(state, attackerCardId);
        CardInstance target = requireCard(state, targetCardId);

        if (!attacker.controllerId().equals(attackerPlayerId))
            throw new IllegalStateException("attacker is not controlled by acting player");
        if (!attacker.isCombatCapable())
            throw new IllegalStateException("attacker is not combat capable");
        if (target.zone() != Zone.KINGDOM || target.isDead())
            throw new IllegalStateException("target is not a valid battlefield target");
        if (target.controllerId().equals(attackerPlayerId))
            throw new IllegalStateException("cannot attack your own card");

        String targetPlayerId = target.controllerId();
        Decision legality = interactions.validate(
                state,
                new ActionIntent(attackerPlayerId, "ATTACK",
                        List.of(attackerCardId), List.of(targetCardId), targetPlayerId)
        );
        if (!legality.allowed())
            throw new IllegalStateException(legality.phase() + ": " + legality.reason());

        if (attacker.definition().strength() == null)
            throw new IllegalStateException("attacker has no combat strength");

        revealToAll(state, attacker);
        revealToAll(state, target);

        return new CombatEncounter(attackerPlayerId, targetPlayerId, attackerCardId, targetCardId);
    }

    /**
     * A defender may replace the original target during the reaction window.
     * The interceptor must already be deployed and have DEFEND or GUARD.
     */
    public void intercept(GameState state, CombatEncounter encounter, String interceptorCardId) {
        requireOpen(encounter);
        CardInstance interceptor = requireCard(state, interceptorCardId);

        if (!interceptor.controllerId().equals(encounter.targetPlayerId()))
            throw new IllegalStateException("interceptor is not controlled by defender");
        if (!interceptor.isCombatCapable())
            throw new IllegalStateException("interceptor is not combat capable");
        if (interceptor.definition().strength() == null)
            throw new IllegalStateException("interceptor has no combat strength");
        if (!(interceptor.definition().hasCapability("DEFEND")
                || interceptor.definition().hasCapability("GUARD"))) {
            throw new IllegalStateException("interceptor lacks DEFEND or GUARD");
        }

        revealToAll(state, interceptor);
        encounter.interceptWith(interceptorCardId);
    }

    /**
     * The attacking unit can withdraw while the reaction window is still open.
     * Future effects may impose pursuit or retreat penalties.
     */
    public void retreat(GameState state, CombatEncounter encounter) {
        requireOpen(encounter);
        CardInstance attacker = requireCard(state, encounter.attackerCardId());
        if (!attacker.isCombatCapable())
            throw new IllegalStateException("attacker cannot retreat");
        attacker.addState("RETREATED");
        encounter.markRetreated();
    }

    /**
     * Resolve one deterministic exchange. Both sides deal damage
     * simultaneously if they can fight back.
     */
    public CombatResult resolve(GameState state, CombatEncounter encounter) {
        requireOpen(encounter);

        CardInstance attacker = requireCard(state, encounter.attackerCardId());
        CardInstance defender = requireCard(state, encounter.defendingCardId());

        if (!attacker.isCombatCapable())
            throw new IllegalStateException("attacker became unable to fight");
        if (defender.zone() != Zone.KINGDOM || defender.isDead())
            throw new IllegalStateException("defender is no longer a valid target");

        int attackerStrength = strength(attacker);

        int damageToDefender = rules.strikeDamage(attackerStrength);
        int damageToAttacker = canFightBack(defender)
                ? rules.strikeDamage(strength(defender))
                : 0;

        defender.addDamage(damageToDefender);
        attacker.addDamage(damageToAttacker);

        boolean defenderDied = applyDeathIfNeeded(state, defender);
        boolean attackerDied = applyDeathIfNeeded(state, attacker);

        if (!defenderDied) defender.refreshWoundState();
        if (!attackerDied) attacker.refreshWoundState();

        encounter.recordExchange();
        encounter.markResolved();

        return new CombatResult(
                encounter.id(),
                attacker.id(),
                defender.id(),
                damageToAttacker,
                damageToDefender,
                attackerDied,
                defenderDied,
                encounter.status()
        );
    }

    /**
     * Regeneration is intentionally gradual. It is not instant damage erasure.
     */
    public int regenerate(CardInstance card) {
        if (card.isDead() || !card.definition().hasCapability("REGENERATE")) return 0;
        int before = card.damage();
        card.healDamage(rules.regenerationPerTurn());
        card.refreshWoundState();
        return before - card.damage();
    }

    public int heal(CardInstance healer, CardInstance target) {
        if (healer.isDead() || target.isDead()) return 0;
        if (!(healer.definition().hasCapability("HEAL BIOLOGICAL")
                || healer.definition().hasCapability("TREAT"))) {
            throw new IllegalStateException("healer lacks a healing capability");
        }
        if (!target.definition().hasProperty("BIOLOGICAL"))
            throw new IllegalStateException("target is not biological");

        int before = target.damage();
        target.healDamage(rules.standardHealingAmount());
        target.refreshWoundState();
        return before - target.damage();
    }

    private boolean applyDeathIfNeeded(GameState state, CardInstance card) {
        if (card.damage() < card.vitality()) return false;

        card.addState("DEAD");
        if (card.definition().hasProperty("BIOLOGICAL")) card.addState("CORPSE");
        if (card.definition().hasProperty("CONSTRUCT")) card.addState("WRECK");

        PlayerState controller = state.player(card.controllerId());
        controller.moveToGraveyard(card);

        for (PlayerState observer : state.players()) observer.reveal(card.id());
        return true;
    }

    private boolean canFightBack(CardInstance defender) {
        return defender.definition().strength() != null
                && (defender.definition().hasCapability("ATTACK")
                    || defender.definition().hasCapability("DEFEND")
                    || defender.definition().hasCapability("GUARD"));
    }

    private int strength(CardInstance card) {
        Integer value = card.definition().strength();
        if (value == null) throw new IllegalStateException(card.definition().name() + " has no strength");
        return value;
    }

    private CardInstance requireCard(GameState state, String id) {
        return state.findCard(id).orElseThrow(() -> new IllegalArgumentException("card not found: " + id));
    }

    private void revealToAll(GameState state, CardInstance card) {
        card.setVisibility(Visibility.PUBLIC);
        for (PlayerState observer : state.players()) observer.reveal(card.id());
    }

    private void requireOpen(CombatEncounter encounter) {
        if (encounter.status() != CombatStatus.REACTION_WINDOW)
            throw new IllegalStateException("combat reaction window is closed");
    }
}
