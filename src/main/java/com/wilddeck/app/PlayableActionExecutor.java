package com.wilddeck.app;

import com.wilddeck.ai.*;
import com.wilddeck.engine.*;

import java.util.*;

public final class PlayableActionExecutor {
    private final GameEngine game = new GameEngine();
    private final WarfareEngine warfare = new WarfareEngine();
    private final KingdomWarfareEngine kingdoms = new KingdomWarfareEngine();
    private final RelationshipEngine relationships = new RelationshipEngine();
    private final AccessEngine access = new AccessEngine();

    public ActionExecutionResult execute(GameState state, String playerId, AiActionProposal proposal) {
        try {
            return switch (proposal.kind()) {
                case DRAW -> draw(state,playerId);
                case PLAY -> play(state,playerId,proposal.cardId(),proposal.hiddenPlay());
                case WORLD_ACTION -> worldAction(state,playerId,proposal);
                case NEGOTIATE -> ActionExecutionResult.ok(
                        proposal.message().isBlank() ? "Negotiation proposed." : proposal.message());
                case PASS -> ActionExecutionResult.ok("Pass.");
            };
        } catch (RuntimeException e) {
            return ActionExecutionResult.fail(e.getMessage());
        }
    }

    public ActionExecutionResult draw(GameState state, String playerId) {
        CardInstance card = game.draw(state,playerId);
        state.addEvent(new GameEvent(
                state.round(),"DRAW",playerId,null,List.of(card.id()),
                state.player(playerId).name() + " drew a card.",Set.of(playerId)));
        return ActionExecutionResult.ok("Drew " + card.definition().name());
    }

    public ActionExecutionResult play(GameState state, String playerId, String cardId, boolean hidden) {
        CardInstance card = game.playFromHand(state,playerId,cardId,hidden);
        Set<String> visible = hidden ? Set.of(playerId) : Set.of();
        state.addEvent(new GameEvent(
                state.round(),hidden ? "HIDDEN_DEPLOYMENT" : "PUBLIC_DEPLOYMENT",
                playerId,null,List.of(card.id()),
                hidden
                        ? "Deployed " + card.definition().name() + " privately."
                        : state.player(playerId).name() + " deployed " + card.definition().name() + ".",
                visible));
        return ActionExecutionResult.ok(
                "Deployed " + card.definition().name() + (hidden ? " privately." : " publicly."));
    }

    public ActionExecutionResult attack(
            GameState state,String playerId,String attackerCardId,String targetCardId
    ) {
        CardInstance target = state.findCard(targetCardId)
                .orElseThrow(() -> new IllegalArgumentException("target not found"));

        CombatEncounter encounter = warfare.declareAttack(
                state,playerId,attackerCardId,targetCardId);

        List<CardInstance> protectors = relationships.protectorsOf(state,targetCardId).stream()
                .filter(c -> !c.id().equals(targetCardId))
                .sorted(Comparator.comparingInt(
                        (CardInstance c) -> c.definition().strength() == null
                                ? 0 : c.definition().strength()).reversed())
                .toList();

        if (!protectors.isEmpty()) {
            warfare.intercept(state,encounter,protectors.get(0).id());
        }

        CombatResult result = warfare.resolve(state,encounter);
        CardInstance attacker = state.findCard(attackerCardId).orElseThrow();
        CardInstance defender = state.findCard(result.defenderCardId()).orElseThrow();

        String summary = "%s attacked %s: %d damage dealt, %d received%s%s."
                .formatted(
                        attacker.definition().name(),
                        defender.definition().name(),
                        result.damageToDefender(),
                        result.damageToAttacker(),
                        result.defenderDied() ? "; defender died" : "",
                        result.attackerDied() ? "; attacker died" : ""
                );

        state.addEvent(new GameEvent(
                state.round(),"COMBAT",playerId,target.controllerId(),
                List.of(attackerCardId,result.defenderCardId()),summary,Set.of()));

        return ActionExecutionResult.ok(summary);
    }

    public ActionExecutionResult regenerate(GameState state,String playerId,String cardId) {
        CardInstance card = controlled(state,playerId,cardId);
        int amount = warfare.regenerate(card);
        if (amount <= 0) return ActionExecutionResult.fail("No regeneration occurred.");
        String summary = card.definition().name() + " regenerated " + amount + " damage.";
        state.addEvent(new GameEvent(
                state.round(),"REGENERATE",playerId,null,List.of(card.id()),summary,Set.of()));
        return ActionExecutionResult.ok(summary);
    }

    public ActionExecutionResult heal(
            GameState state,String playerId,String healerCardId,String targetCardId
    ) {
        CardInstance healer = controlled(state,playerId,healerCardId);
        CardInstance target = controlled(state,playerId,targetCardId);
        int amount = warfare.heal(healer,target);
        String summary = healer.definition().name() + " healed "
                + target.definition().name() + " for " + amount + ".";
        state.addEvent(new GameEvent(
                state.round(),"HEAL",playerId,null,
                List.of(healer.id(),target.id()),summary,Set.of()));
        return ActionExecutionResult.ok(summary);
    }

    public ActionExecutionResult occupy(
            GameState state,String playerId,String occupierCardId,String targetCardId
    ) {
        Occupation occupation = kingdoms.beginOccupation(
                state,playerId,occupierCardId,targetCardId);
        CardInstance target = state.findCard(targetCardId).orElseThrow();
        String summary = state.findCard(occupierCardId).orElseThrow().definition().name()
                + " began occupying " + target.definition().name() + ".";
        state.addEvent(new GameEvent(
                state.round(),"OCCUPATION_STARTED",playerId,target.controllerId(),
                List.of(occupierCardId,targetCardId),summary,Set.of()));
        return ActionExecutionResult.ok(summary + " Occupation " + occupation.id());
    }

    public ActionExecutionResult route(
            GameState state,
            String playerId,
            String targetPlayerId,
            AccessRouteType type,
            List<String> supportCardIds,
            boolean hidden
    ) {
        AccessRoute route = access.establishRoute(
                state,playerId,targetPlayerId,type,supportCardIds,
                hidden ? Visibility.HIDDEN : Visibility.PUBLIC);

        String summary = hidden
                ? "Established a hidden " + type + " route to " + targetPlayerId + "."
                : state.player(playerId).name() + " established a public " + type
                    + " route to " + targetPlayerId + ".";

        state.addEvent(new GameEvent(
                state.round(),"ACCESS_ROUTE",playerId,targetPlayerId,supportCardIds,
                summary,hidden ? Set.of(playerId) : Set.of()));

        return ActionExecutionResult.ok(summary + " Route " + route.id());
    }

    public ActionExecutionResult attach(
            GameState state,String playerId,String sourceCardId,String targetCardId
    ) {
        CardRelation relation = relationships.attach(state,playerId,sourceCardId,targetCardId);
        String summary = state.findCard(sourceCardId).orElseThrow().definition().name()
                + " attached to "
                + state.findCard(targetCardId).orElseThrow().definition().name() + ".";
        state.addEvent(new GameEvent(
                state.round(),"ATTACHMENT",playerId,null,List.of(sourceCardId,targetCardId),
                summary,Set.of()));
        return ActionExecutionResult.ok(summary + " Relation " + relation.id());
    }

    public ActionExecutionResult conquer(GameState state,String playerId,String defenderId) {
        KingdomWarfareResult result = kingdoms.conquer(state,playerId,defenderId);
        String summary = state.player(playerId).name() + " conquered "
                + state.player(defenderId).name() + ", taking "
                + result.transferredCards() + " deployed assets.";
        state.addEvent(new GameEvent(
                state.round(),"CONQUEST",playerId,defenderId,List.of(),summary,Set.of()));
        return ActionExecutionResult.ok(summary);
    }

    public ActionExecutionResult annihilate(GameState state,String playerId,String defenderId) {
        KingdomWarfareResult result = kingdoms.annihilate(state,playerId,defenderId);
        String summary = state.player(playerId).name() + " annihilated "
                + state.player(defenderId).name() + ", destroying "
                + result.destroyedCards() + " assets.";
        state.addEvent(new GameEvent(
                state.round(),"ANNIHILATION",playerId,defenderId,List.of(),summary,Set.of()));
        return ActionExecutionResult.ok(summary);
    }

    private ActionExecutionResult worldAction(
            GameState state,String playerId,AiActionProposal proposal
    ) {
        String verb = proposal.verb() == null ? "" : proposal.verb().trim().toUpperCase(Locale.ROOT);
        List<String> sources = proposal.sourceCardIds();
        List<String> targets = proposal.targetCardIds();

        return switch (verb) {
            case "ATTACK" -> requireOneEach(state,playerId,sources,targets);
            case "REGENERATE" -> regenerate(state,playerId,first(sources,"source"));
            case "TREAT", "HEAL BIOLOGICAL" -> heal(
                    state,playerId,first(sources,"healer"),first(targets,"target"));
            case "OCCUPY" -> occupy(
                    state,playerId,first(sources,"occupier"),first(targets,"target"));
            case "ATTACH", "EQUIP" -> attach(
                    state,playerId,first(sources,"attachment"),first(targets,"target"));
            case "TUNNEL" -> route(
                    state,playerId,requiredPlayer(proposal),AccessRouteType.TUNNEL,
                    sources,true);
            case "INFILTRATE", "SMUGGLE" -> route(
                    state,playerId,requiredPlayer(proposal),AccessRouteType.INFILTRATION,
                    sources,true);
            default -> ActionExecutionResult.fail(
                    "The alpha executor does not resolve " + verb + " yet.");
        };
    }

    private ActionExecutionResult requireOneEach(
            GameState state,String playerId,List<String> sources,List<String> targets
    ) {
        return attack(state,playerId,first(sources,"attacker"),first(targets,"target"));
    }

    private String requiredPlayer(AiActionProposal proposal) {
        if (proposal.targetPlayerId() == null) {
            throw new IllegalArgumentException("target player is required");
        }
        return proposal.targetPlayerId();
    }

    private String first(List<String> ids,String label) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return ids.get(0);
    }

    private CardInstance controlled(GameState state,String playerId,String cardId) {
        CardInstance card = state.findCard(cardId)
                .orElseThrow(() -> new IllegalArgumentException("card not found"));
        if (!card.controllerId().equals(playerId)) {
            throw new IllegalStateException("player does not control " + card.definition().name());
        }
        return card;
    }
}
