package com.wilddeck.engine;

import java.util.*;

/**
 * Resolves whether a player has a legitimate physical/covert route to an enemy
 * Kingdom or target. Access is separate from success: reaching a target does
 * not guarantee that the attempted action works.
 */
public final class AccessEngine {

    public AccessRoute establishRoute(
            GameState state,
            String actorId,
            String targetPlayerId,
            AccessRouteType type,
            Collection<String> supportCardIds,
            Visibility visibility
    ) {
        state.player(actorId);
        state.player(targetPlayerId);
        if (actorId.equals(targetPlayerId)) {
            throw new IllegalArgumentException("enemy access route must cross Kingdoms");
        }

        List<CardInstance> supports = new ArrayList<>();
        for (String id : supportCardIds) {
            CardInstance card = state.findCard(id)
                    .orElseThrow(() -> new IllegalArgumentException("support card not found: " + id));
            if (!card.controllerId().equals(actorId)) {
                throw new IllegalStateException("route support is not controlled by actor");
            }
            if (card.zone() != Zone.KINGDOM && card.zone() != Zone.ATTACHED) {
                throw new IllegalStateException("route support must be deployed");
            }
            if (card.isDead()) throw new IllegalStateException("dead card cannot support a route");
            supports.add(card);
        }

        if (!supportsType(type, supports)) {
            throw new IllegalStateException("support cards do not establish " + type + " access");
        }

        AccessRoute route = new AccessRoute(
                actorId, actorId, targetPlayerId, type, supportCardIds, visibility);
        state.addAccessRoute(route);
        return route;
    }

    public AccessDecision canReach(
            GameState state,
            String actorId,
            String sourceCardId,
            String targetCardId
    ) {
        PlayerState actor = state.player(actorId);
        CardInstance source = state.findCard(sourceCardId)
                .orElseThrow(() -> new IllegalArgumentException("source not found: " + sourceCardId));
        CardInstance target = state.findCard(targetCardId)
                .orElseThrow(() -> new IllegalArgumentException("target not found: " + targetCardId));

        if (!source.controllerId().equals(actorId)) {
            return AccessDecision.deny("actor does not control source");
        }
        if (source.zone() == Zone.HAND || source.isDead()) {
            return AccessDecision.deny("source is not deployed and active");
        }

        String targetPlayerId = target.controllerId();
        if (actorId.equals(targetPlayerId)) {
            return AccessDecision.allow("target is inside actor's own Kingdom");
        }

        if (target.visibility() == Visibility.HIDDEN && !actor.knows(target.id())) {
            return AccessDecision.deny("target is hidden and has not been discovered");
        }

        Optional<CardInstance> container = new RelationshipEngine().containerOf(state, target.id());

        for (AccessRoute route : state.accessRoutes()) {
            if (route.connects(actorId, targetPlayerId) && routeStillSupported(state, route)) {
                return AccessDecision.allow("reachable through " + route.type(), route.id());
            }
        }

        if (container.isPresent()) {
            if (breachTraversalPossible(source, container.get())) {
                return AccessDecision.allow("source can pass through a breach in the containing asset");
            }
            return AccessDecision.deny("target is contained and no legitimate route bypasses containment");
        }

        if (target.visibility() == Visibility.PUBLIC) {
            return AccessDecision.allow("target is publicly reachable on the battlefield");
        }

        if (breachTraversalPossible(source, target)) {
            return AccessDecision.allow("source can exploit a physical breach");
        }

        return AccessDecision.deny("no legitimate route to enemy target");
    }

    public void deactivateRoute(GameState state, String routeId) {
        AccessRoute route = state.accessRoute(routeId)
                .orElseThrow(() -> new IllegalArgumentException("route not found: " + routeId));
        route.deactivate();
    }

    private boolean supportsType(AccessRouteType type, List<CardInstance> cards) {
        return switch (type) {
            case TUNNEL -> {
                boolean createsTunnel = cards.stream().anyMatch(c ->
                        c.definition().hasCapability("TUNNEL")
                                || c.definition().hasCapability("BURROW"));
                boolean mappedTunnelInfiltration =
                        cards.stream().anyMatch(c -> c.definition().hasCapability("NAVIGATE TUNNELS"))
                        && cards.stream().anyMatch(c ->
                            c.definition().hasCapability("INFILTRATE")
                                    || c.definition().hasCapability("SMUGGLE"));
                yield createsTunnel || mappedTunnelInfiltration;
            }
            case PORTAL -> {
                boolean links = cards.stream().anyMatch(c ->
                        c.definition().hasCapability("LINK LOCATIONS"));
                boolean opens = cards.stream().anyMatch(c ->
                        c.definition().hasCapability("OPEN GATE")
                                || c.definition().hasCapability("TELEPORT"));
                yield links && opens;
            }
            case INFILTRATION -> cards.stream().anyMatch(c ->
                    c.definition().hasCapability("INFILTRATE")
                            || c.definition().hasCapability("SMUGGLE")
                            || c.definition().hasCapability("CONCEAL")
                            || c.definition().hasCapability("DISGUISE"));
        };
    }

    private boolean hasRoute(GameState state, String actorId, String targetPlayerId) {
        return state.accessRoutes().stream()
                .anyMatch(r -> r.connects(actorId, targetPlayerId) && routeStillSupported(state, r));
    }

    private boolean routeStillSupported(GameState state, AccessRoute route) {
        for (String id : route.supportCardIds()) {
            CardInstance card = state.findCard(id).orElse(null);
            if (card == null || card.isDead()
                    || !card.controllerId().equals(route.ownerPlayerId())
                    || (card.zone() != Zone.KINGDOM && card.zone() != Zone.ATTACHED)) {
                return false;
            }
        }
        return route.active();
    }

    /**
     * Generic Bone-Colossus-style breach logic based on properties/capabilities,
     * not a card name or recipe.
     */
    private boolean breachTraversalPossible(CardInstance source, CardInstance target) {
        boolean targetBreached = target.hasState("BREACHED") || target.hasState("DAMAGED_OPENING");
        boolean canDisperseAndReform = source.definition().hasProperty("BONE")
                && source.definition().hasCapability("REASSEMBLE");
        return targetBreached && canDisperseAndReform;
    }
}
