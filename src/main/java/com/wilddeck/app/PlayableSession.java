package com.wilddeck.app;

import com.wilddeck.ai.*;
import com.wilddeck.engine.*;

import java.util.*;

public final class PlayableSession {
    private final GameState state;
    private final List<String> turnOrder;
    private final String humanPlayerId;
    private final GameEngine game = new GameEngine();
    private final ProductionEngine production = new ProductionEngine();
    private final WarfareEngine warfare = new WarfareEngine();
    private final KingdomWarfareEngine kingdoms = new KingdomWarfareEngine();
    private final PlayableActionExecutor executor = new PlayableActionExecutor();
    private final KingdomManagement management = new KingdomManagement();
    private final HeuristicOpponentAgent heuristic = new HeuristicOpponentAgent();
    private final WildDeckAiService groqAi;
    private int turnIndex;
    private AiDecisionSource lastAiDecisionSource;
    private String lastAiDiagnostic = "";

    public PlayableSession(
            long seed,
            String humanPlayerId,
            List<PlayerState> players,
            WildDeckAiService groqAi
    ) {
        if (players.size() < 2) throw new IllegalArgumentException("playable session needs 2+ players");
        this.humanPlayerId = Objects.requireNonNull(humanPlayerId);
        this.turnOrder = players.stream().map(PlayerState::id).toList();
        if (!turnOrder.contains(humanPlayerId)) throw new IllegalArgumentException("human player missing");
        this.groqAi = groqAi;

        this.state = new GameState(
                GameRules.alphaV1(),
                players,
                new AlphaDeckFactory().create(seed),
                turnOrder.get(0)
        );

        game.initializeResources(state);
        dealOpeningHands(7);
        game.startRound(state,turnOrder.get(0));
        production.collectForRound(state);
        state.addEvent(new GameEvent(
                state.round(),"MATCH_STARTED",null,null,List.of(),
                "Wild Deck alpha match started. Seed " + seed + ".",Set.of()));
    }

    public static PlayableSession standard(long seed, WildDeckAiService groqAi) {
        return new PlayableSession(
                seed,
                "player",
                List.of(
                        new PlayerState("player","Player"),
                        new PlayerState("asha","Asha"),
                        new PlayerState("brian","Brian")
                ),
                groqAi
        );
    }

    public GameState state() { return state; }
    public PlayableActionExecutor executor() { return executor; }
    public KingdomManagement management() { return management; }
    public String humanPlayerId() { return humanPlayerId; }
    public String currentPlayerId() { return state.activePlayerId(); }
    public boolean humanTurn() { return humanPlayerId.equals(currentPlayerId()); }
    public AiDecisionSource lastAiDecisionSource() { return lastAiDecisionSource; }
    public String lastAiDiagnostic() { return lastAiDiagnostic; }

    public ActionExecutionResult endTurn() {
        if (state.winner().isPresent()) {
            return ActionExecutionResult.fail("The match already has a winner.");
        }

        int checked = 0;
        boolean wrapped = false;
        do {
            int previous = turnIndex;
            turnIndex = (turnIndex + 1) % turnOrder.size();
            if (turnIndex <= previous) wrapped = true;
            checked++;
        } while (checked <= turnOrder.size()
                && state.player(turnOrder.get(turnIndex)).isEliminated());

        if (checked > turnOrder.size()) {
            return ActionExecutionResult.fail("No active players remain.");
        }

        boolean newRound = wrapped;
        String next = turnOrder.get(turnIndex);

        if (newRound) {
            game.advanceRound(state,next);
            production.collectForRound(state);
            resolveRoundStartMaintenance();
            management.onRound(state,humanPlayerId);
            state.addEvent(new GameEvent(
                    state.round(),"ROUND_STARTED",null,null,List.of(),
                    "Round " + state.round() + " started.",Set.of()));
        } else {
            state.beginTurn(next);
        }

        return ActionExecutionResult.ok("Turn: " + state.player(next).name());
    }

    public ActionExecutionResult runCurrentAiTurn() {
        String playerId = currentPlayerId();
        if (humanPlayerId.equals(playerId)) {
            return ActionExecutionResult.fail("It is the human player's turn.");
        }

        AiActionProposal proposal;
        lastAiDiagnostic = "";
        if (groqAi != null) {
            try {
                proposal = groqAi.chooseOpponentAction(state,playerId);
                lastAiDecisionSource = AiDecisionSource.GROQ;
            } catch (RuntimeException e) {
                proposal = heuristic.choose(state,playerId);
                lastAiDecisionSource = AiDecisionSource.HEURISTIC_AFTER_GROQ_ERROR;
                lastAiDiagnostic = compactDiagnostic(e);
            }
        } else {
            proposal = heuristic.choose(state,playerId);
            lastAiDecisionSource = AiDecisionSource.HEURISTIC;
        }

        ActionExecutionResult result = executor.execute(state,playerId,proposal);
        if (!result.success() && groqAi != null
                && lastAiDecisionSource == AiDecisionSource.GROQ) {
            AiActionProposal fallback = heuristic.choose(state,playerId);
            result = executor.execute(state,playerId,fallback);
            lastAiDecisionSource = AiDecisionSource.HEURISTIC_AFTER_GROQ_REJECTION;
            lastAiDiagnostic = "Groq proposal failed Java execution; deterministic fallback used.";
        }

        String aiName = state.player(playerId).name();
        state.addEvent(new GameEvent(
                state.round(),"AI_DECISION",playerId,null,List.of(),
                aiName + ": " + proposal.strategicSummary(),Set.of(playerId)));

        return result;
    }

    public List<Occupation> activeOccupations() {
        return state.occupations().stream()
                .filter(o -> o.status() == OccupationStatus.ACTIVE)
                .toList();
    }

    private static String compactDiagnostic(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        String compact = message.replaceAll("\\s+", " ").trim();
        if (compact.length() > 280) compact = compact.substring(0,280) + "...";
        return e.getClass().getSimpleName() + ": " + compact;
    }

    private void dealOpeningHands(int handSize) {
        for (int i=0;i<handSize;i++) {
            for (String playerId : turnOrder) {
                if (state.deckSize() == 0) return;
                CardInstance card = state.drawTop();
                state.player(playerId).addToHand(card);
            }
        }
    }

    private void resolveRoundStartMaintenance() {
        for (PlayerState player : state.players()) {
            if (player.isEliminated()) continue;
            for (CardInstance card : player.kingdom()) {
                if (card.damage() > 0 && card.definition().hasCapability("REGENERATE")) {
                    int healed = warfare.regenerate(card);
                    if (healed > 0) {
                        state.addEvent(new GameEvent(
                                state.round(),"AUTO_REGENERATE",player.id(),null,
                                List.of(card.id()),
                                card.definition().name() + " regenerated " + healed + " damage.",
                                card.visibility() == Visibility.PUBLIC ? Set.of() : Set.of(player.id())));
                    }
                }
            }
        }

        for (Occupation occupation : List.copyOf(state.occupations())) {
            if (occupation.status() != OccupationStatus.ACTIVE
                    || !occupation.survivedTo(state.round())) continue;
            try {
                CardInstance captured = kingdoms.completeOccupation(state,occupation.id());
                state.addEvent(new GameEvent(
                        state.round(),"CAPTURE_COMPLETED",
                        occupation.occupierPlayerId(),occupation.defenderPlayerId(),
                        List.of(captured.id()),
                        state.player(occupation.occupierPlayerId()).name()
                                + " captured " + captured.definition().name() + ".",Set.of()));
            } catch (RuntimeException ignored) {
                // KingdomWarfareEngine marks a failed occupation as broken when appropriate.
            }
        }
    }
}
