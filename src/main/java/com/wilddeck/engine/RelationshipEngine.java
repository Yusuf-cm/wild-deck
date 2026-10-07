package com.wilddeck.engine;

import java.util.*;

/**
 * Lightweight world relationships. These replace large subsystems with explicit
 * card-to-card facts such as ATTACHED_TO, PROTECTS and CONTAINS.
 */
public final class RelationshipEngine {

    public CardRelation attach(GameState state, String actorId, String sourceCardId, String targetCardId) {
        CardInstance source = controlledDeployed(state, actorId, sourceCardId);
        CardInstance target = controlledDeployed(state, actorId, targetCardId);

        boolean attachable = source.definition().hasProperty("EQUIPMENT")
                || source.definition().category().toUpperCase().contains("EQUIPMENT")
                || source.definition().hasCapability("ATTACH")
                || source.definition().hasCapability("EQUIP");
        if (!attachable) {
            throw new IllegalStateException("source has no attachment basis");
        }

        CardRelation relation = new CardRelation(CardRelationType.ATTACHED_TO, source.id(), target.id());
        state.addCardRelation(relation);
        source.setZone(Zone.ATTACHED);
        return relation;
    }

    public CardRelation protect(GameState state, String actorId, String protectorCardId, String targetCardId) {
        CardInstance protector = controlledDeployed(state, actorId, protectorCardId);
        controlledDeployed(state, actorId, targetCardId);

        if (!(protector.definition().hasCapability("DEFEND")
                || protector.definition().hasCapability("GUARD")
                || protector.definition().hasCapability("BLOCK")
                || protector.definition().hasCapability("FORTIFY"))) {
            throw new IllegalStateException("protector lacks defensive capability");
        }

        CardRelation relation = new CardRelation(CardRelationType.PROTECTS, protectorCardId, targetCardId);
        state.addCardRelation(relation);
        return relation;
    }

    public CardRelation contain(GameState state, String actorId, String containerCardId, String containedCardId) {
        CardInstance container = controlledDeployed(state, actorId, containerCardId);
        CardInstance contained = controlledDeployed(state, actorId, containedCardId);

        if (!(container.definition().hasCapability("SHELTER")
                || container.definition().hasCapability("HOLD")
                || container.definition().hasCapability("CONFINE")
                || container.definition().hasCapability("CONTAIN"))) {
            throw new IllegalStateException("container lacks containment capability");
        }

        CardRelation relation = new CardRelation(CardRelationType.CONTAINS, container.id(), contained.id());
        state.addCardRelation(relation);
        return relation;
    }

    public List<CardInstance> protectorsOf(GameState state, String targetCardId) {
        return state.relationsTo(targetCardId, CardRelationType.PROTECTS).stream()
                .map(r -> state.findCard(r.sourceCardId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(CardInstance::isCombatCapable)
                .toList();
    }

    public Optional<CardInstance> containerOf(GameState state, String cardId) {
        return state.relationsTo(cardId, CardRelationType.CONTAINS).stream()
                .map(r -> state.findCard(r.sourceCardId()).orElse(null))
                .filter(Objects::nonNull)
                .findFirst();
    }

    private CardInstance controlledDeployed(GameState state, String actorId, String cardId) {
        CardInstance card = state.findCard(cardId)
                .orElseThrow(() -> new IllegalArgumentException("card not found: " + cardId));
        if (!card.controllerId().equals(actorId)) {
            throw new IllegalStateException("actor does not control " + card.definition().name());
        }
        if (card.zone() != Zone.KINGDOM && card.zone() != Zone.ATTACHED) {
            throw new IllegalStateException("card must already exist in the Kingdom");
        }
        if (card.isDead()) throw new IllegalStateException("dead card cannot form relationship");
        return card;
    }
}
