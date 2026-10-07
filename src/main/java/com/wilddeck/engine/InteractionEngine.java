package com.wilddeck.engine;

import java.util.*;

public final class InteractionEngine {
    private static final Set<String> HOSTILE = Set.of(
            "ATTACK","ASSASSINATE","POISON BIOLOGICAL","INFECT BIOLOGICAL",
            "STEAL","CURSE","SABOTAGE","CORRODE","BURN"
    );

    public Decision validate(GameState state, ActionIntent intent) {
        if (intent.actingPlayerId() == null || intent.actingPlayerId().isBlank()
                || intent.verb() == null || intent.normalizedVerb().isBlank()) {
            return Decision.reject("IDEA","intent must identify actor and verb");
        }

        List<CardInstance> sources = new ArrayList<>();
        for (String id : intent.sourceCardIds()) {
            CardInstance card = state.findCard(id).orElse(null);
            if (card == null) return Decision.reject("REQUIREMENTS","missing source " + id);
            if (!card.controllerId().equals(intent.actingPlayerId()))
                return Decision.reject("LEGALITY","actor does not control source " + id);
            if (card.zone() == Zone.HAND)
                return Decision.reject("LEGALITY","cards cannot perform world actions from hand");
            sources.add(card);
        }

        if (sources.stream().noneMatch(c -> c.definition().hasCapability(intent.normalizedVerb()))) {
            return Decision.reject("REQUIREMENTS","no deployed source has capability " + intent.normalizedVerb());
        }

        if (intent.targetPlayerId() != null && HOSTILE.contains(intent.normalizedVerb())) {
            Decision contractDecision = new ContractEngine().validate(
                    state,
                    new ContractActionRequest(
                            intent.actingPlayerId(),
                            ContractActionType.HOSTILE_DIRECT,
                            intent.targetPlayerId(),
                            null,
                            null
                    )
            );
            if (!contractDecision.allowed()) return contractDecision;
        }

        AccessEngine access = new AccessEngine();
        for (String id : intent.targetCardIds()) {
            CardInstance target = state.findCard(id).orElse(null);
            if (target == null) return Decision.reject("TARGET_ACCESS","missing target " + id);

            if (!target.controllerId().equals(intent.actingPlayerId()) && !sources.isEmpty()) {
                AccessDecision reach = access.canReach(
                        state, intent.actingPlayerId(), sources.get(0).id(), target.id());
                if (!reach.allowed()) {
                    return Decision.reject("TARGET_ACCESS", reach.reason());
                }
            }
        }

        return Decision.allow();
    }

    /**
     * Generic Conservation Rule validator.
     * No card-name combo is hard-coded.
     */
    public Decision validateTraitEngineering(
            GameState state,
            String actingPlayerId,
            String targetCardId,
            String requestedTrait,
            List<String> templateCardIds,
            List<String> toolCardIds
    ) {
        CardInstance target = state.findCard(targetCardId).orElse(null);
        if (target == null) return Decision.reject("TARGET_ACCESS","target not found");
        if (!target.controllerId().equals(actingPlayerId))
            return Decision.reject("LEGALITY","actor does not control target");
        if (target.zone() == Zone.HAND)
            return Decision.reject("LEGALITY","target must be deployed first");
        if (!target.definition().hasProperty("BIOLOGICAL"))
            return Decision.reject("REQUIREMENTS","target is not biological");

        String trait = requestedTrait.trim().toUpperCase();
        List<CardInstance> templates = controlledDeployed(state, actingPlayerId, templateCardIds);
        if (templates == null) return Decision.reject("LEGALITY","invalid template");
        boolean traitExists = templates.stream().anyMatch(c ->
                c.definition().hasProperty(trait) || c.definition().hasCapability(trait));
        if (!traitExists) return Decision.reject("REQUIREMENTS",
                "Conservation Rule: requested trait has no legitimate source");

        List<CardInstance> tools = controlledDeployed(state, actingPlayerId, toolCardIds);
        if (tools == null) return Decision.reject("LEGALITY","invalid tool");
        boolean canAlter = tools.stream().anyMatch(c ->
                c.definition().hasCapability("ALTER BIOLOGY") || c.definition().hasCapability("MUTATE"));
        boolean canEngineer = tools.stream().anyMatch(c ->
                c.definition().hasCapability("ENGINEER TRAITS") || c.definition().hasCapability("STABILIZE MUTATION"));

        if (!canAlter) return Decision.reject("REQUIREMENTS","no biological alteration mechanism");
        if (!canEngineer) return Decision.reject("REQUIREMENTS","no engineering/stabilization specialist");
        return Decision.allow();
    }

    private List<CardInstance> controlledDeployed(GameState state, String actor, List<String> ids) {
        List<CardInstance> out = new ArrayList<>();
        for (String id : ids) {
            CardInstance card = state.findCard(id).orElse(null);
            if (card == null || !card.controllerId().equals(actor) || card.zone() == Zone.HAND) return null;
            out.add(card);
        }
        return out;
    }
}
