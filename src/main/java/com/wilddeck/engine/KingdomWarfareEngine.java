package com.wilddeck.engine;

import java.util.*;

/**
 * Kingdom-level warfare: occupation, capture, conquest, annihilation and vassalage.
 *
 * Alpha principles:
 * - Capture is delayed: an occupier must survive into a later round.
 * - Conquest/annihilation require the defender's effective military defense to be broken.
 * - Forced political outcomes require a completed occupation as a physical foothold.
 * - Voluntary vassalage is always possible between surviving kingdoms.
 */
public final class KingdomWarfareEngine {

    public Occupation beginOccupation(
            GameState state,
            String occupierPlayerId,
            String occupierCardId,
            String targetCardId
    ) {
        PlayerState occupierPlayer = state.player(occupierPlayerId);
        CardInstance occupier = requireCard(state, occupierCardId);
        CardInstance target = requireCard(state, targetCardId);

        if (!occupier.controllerId().equals(occupierPlayer.id()))
            throw new IllegalStateException("occupier is not controlled by acting player");
        if (!occupier.isCombatCapable())
            throw new IllegalStateException("occupier is not combat capable");
        if (!occupier.definition().hasCapability("OCCUPY"))
            throw new IllegalStateException("occupier lacks OCCUPY");
        if (target.zone() != Zone.KINGDOM || target.isDead())
            throw new IllegalStateException("target is not a capturable Kingdom asset");
        if (target.controllerId().equals(occupierPlayerId))
            throw new IllegalStateException("cannot occupy your own asset");
        if (state.blocksHostility(occupierPlayerId, target.controllerId()))
            throw new IllegalStateException("active non-aggression contract blocks occupation");
        if (!isSecuredForOccupation(target))
            throw new IllegalStateException("target is still capable of resisting occupation");

        String defenderPlayerId = target.controllerId();
        Occupation occupation = new Occupation(
                occupierPlayerId,
                defenderPlayerId,
                occupierCardId,
                targetCardId,
                state.round()
        );

        occupier.addState("OCCUPYING");
        target.addState("OCCUPIED");
        occupier.setVisibility(Visibility.PUBLIC);
        target.setVisibility(Visibility.PUBLIC);
        revealToAll(state, occupier);
        revealToAll(state, target);
        state.addOccupation(occupation);
        return occupation;
    }

    public void breakOccupation(GameState state, String occupationId) {
        Occupation occupation = requireOccupation(state, occupationId);
        if (occupation.status() != OccupationStatus.ACTIVE) return;

        state.findCard(occupation.occupierCardId()).ifPresent(c -> c.removeState("OCCUPYING"));
        state.findCard(occupation.targetCardId()).ifPresent(c -> c.removeState("OCCUPIED"));
        occupation.breakOccupation();
    }

    /**
     * Counterattack window is represented by the required round boundary.
     * If the occupier dies or leaves the Kingdom before resolution, capture fails.
     */
    public CardInstance completeOccupation(GameState state, String occupationId) {
        Occupation occupation = requireOccupation(state, occupationId);
        if (occupation.status() != OccupationStatus.ACTIVE)
            throw new IllegalStateException("occupation is not active");
        if (!occupation.survivedTo(state.round()))
            throw new IllegalStateException("occupation must survive into a later round");

        CardInstance occupier = requireCard(state, occupation.occupierCardId());
        CardInstance target = requireCard(state, occupation.targetCardId());

        if (!occupier.isCombatCapable()
                || !occupier.controllerId().equals(occupation.occupierPlayerId())) {
            breakOccupation(state, occupationId);
            throw new IllegalStateException("occupier did not survive the counterattack window");
        }

        if (target.zone() != Zone.KINGDOM
                || !target.controllerId().equals(occupation.defenderPlayerId())) {
            breakOccupation(state, occupationId);
            throw new IllegalStateException("target is no longer controlled by the defender");
        }

        PlayerState defender = state.player(occupation.defenderPlayerId());
        PlayerState attacker = state.player(occupation.occupierPlayerId());

        defender.removeFromKingdom(target.id());
        target.setOwnerId(attacker.id());
        target.setControllerId(attacker.id());
        target.removeState("OCCUPIED");
        occupier.removeState("OCCUPYING");
        attacker.deploy(target, false);
        revealToAll(state, target);

        occupation.complete();
        return target;
    }

    /**
     * A Kingdom is considered militarily broken when it has no deployed card
     * that is currently combat capable and able to ATTACK, DEFEND or GUARD.
     */
    public boolean defenseBroken(GameState state, String defenderPlayerId) {
        PlayerState defender = state.player(defenderPlayerId);
        return defender.kingdom().stream().noneMatch(this::isEffectiveDefender);
    }

    public KingdomWarfareResult conquer(
            GameState state,
            String conquerorId,
            String defenderId
    ) {
        requireForcedOutcome(state, conquerorId, defenderId);

        PlayerState conqueror = state.player(conquerorId);
        PlayerState defender = state.player(defenderId);

        int transferred = 0;
        for (CardInstance card : List.copyOf(defender.kingdom())) {
            defender.removeFromKingdom(card.id());
            card.setOwnerId(conqueror.id());
            card.setControllerId(conqueror.id());
            card.removeState("OCCUPIED");
            card.removeState("OCCUPYING");
            conqueror.deploy(card, false);
            revealToAll(state, card);
            transferred++;
        }

        Map<ResourceType,Integer> resources = defender.resources().drainAll();
        resources.forEach(conqueror.resources()::add);

        defender.becomeConquered(conquerorId);

        return new KingdomWarfareResult(
                conquerorId, defenderId, defender.kingdomStatus(),
                transferred, 0, resources
        );
    }

    public KingdomWarfareResult annihilate(
            GameState state,
            String attackerId,
            String defenderId
    ) {
        requireForcedOutcome(state, attackerId, defenderId);

        PlayerState defender = state.player(defenderId);
        int destroyed = 0;

        for (CardInstance card : List.copyOf(defender.kingdom())) {
            card.addState("DEAD");
            if (card.definition().hasProperty("BIOLOGICAL")) card.addState("CORPSE");
            else if (card.definition().hasProperty("CONSTRUCT")) card.addState("WRECK");
            else card.addState("RUINED");
            defender.moveToGraveyard(card);
            revealToAll(state, card);
            destroyed++;
        }

        defender.resources().drainAll();
        defender.becomeAnnihilated();

        return new KingdomWarfareResult(
                attackerId, defenderId, defender.kingdomStatus(),
                0, destroyed, Map.of()
        );
    }

    /**
     * Peaceful/negotiated vassalage. No military defeat is required because
     * both sides are consenting to the political relationship.
     */
    public KingdomWarfareResult acceptVassalage(
            GameState state,
            String overlordId,
            String vassalId
    ) {
        PlayerState overlord = state.player(overlordId);
        PlayerState vassal = state.player(vassalId);
        if (overlord.isEliminated() || vassal.isEliminated())
            throw new IllegalStateException("eliminated kingdoms cannot form vassalage");
        if (overlordId.equals(vassalId))
            throw new IllegalArgumentException("kingdom cannot vassalize itself");

        vassal.becomeVassal(overlordId);
        return new KingdomWarfareResult(
                overlordId, vassalId, vassal.kingdomStatus(),
                0, 0, Map.of()
        );
    }

    /**
     * Forced vassalage uses the same military prerequisites as conquest,
     * but leaves the vassal's assets and internal management intact.
     */
    public KingdomWarfareResult imposeVassalage(
            GameState state,
            String overlordId,
            String vassalId
    ) {
        requireForcedOutcome(state, overlordId, vassalId);
        PlayerState vassal = state.player(vassalId);
        vassal.becomeVassal(overlordId);

        return new KingdomWarfareResult(
                overlordId, vassalId, vassal.kingdomStatus(),
                0, 0, Map.of()
        );
    }

    private void requireForcedOutcome(GameState state, String attackerId, String defenderId) {
        if (attackerId.equals(defenderId))
            throw new IllegalArgumentException("kingdom cannot target itself");
        PlayerState attacker = state.player(attackerId);
        PlayerState defender = state.player(defenderId);

        if (attacker.isEliminated())
            throw new IllegalStateException("eliminated kingdom cannot impose outcomes");
        if (defender.isEliminated())
            throw new IllegalStateException("defender is already eliminated");
        if (state.blocksHostility(attackerId, defenderId))
            throw new IllegalStateException("active non-aggression contract blocks forced outcome");
        if (!defenseBroken(state, defenderId))
            throw new IllegalStateException("defender still has effective military defense");
        if (!state.hasCompletedOccupation(attackerId, defenderId))
            throw new IllegalStateException("attacker lacks a completed occupation foothold");
    }

    private boolean isSecuredForOccupation(CardInstance target) {
        if (target.definition().strength() == null) return true;
        return target.hasState("INCAPACITATED")
                || target.hasState("PETRIFIED")
                || target.hasState("CRITICALLY_WOUNDED");
    }

    private boolean isEffectiveDefender(CardInstance card) {
        return card.isCombatCapable()
                && card.definition().strength() != null
                && (card.definition().hasCapability("ATTACK")
                    || card.definition().hasCapability("DEFEND")
                    || card.definition().hasCapability("GUARD"));
    }

    private CardInstance requireCard(GameState state, String id) {
        return state.findCard(id)
                .orElseThrow(() -> new IllegalArgumentException("card not found: " + id));
    }

    private Occupation requireOccupation(GameState state, String id) {
        return state.occupation(id)
                .orElseThrow(() -> new IllegalArgumentException("occupation not found: " + id));
    }

    private void revealToAll(GameState state, CardInstance card) {
        card.setVisibility(Visibility.PUBLIC);
        for (PlayerState observer : state.players()) observer.reveal(card.id());
    }
}
