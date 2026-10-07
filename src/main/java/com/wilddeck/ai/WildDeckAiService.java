package com.wilddeck.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wilddeck.engine.*;

import java.util.*;

/**
 * AI orchestration for Wild Deck.
 *
 * Language models propose and translate. Java remains authoritative.
 */
public final class WildDeckAiService {
    private static final String ACTION_SYSTEM_PROMPT = """
        You are the Wild Deck command translator.
        Convert the player's natural-language command into ONE structured game proposal.
        Never invent cards, card IDs, properties, resources, access routes, or hidden knowledge.
        Use only IDs and capabilities present in the supplied player-specific game view.
        Do not decide whether an action succeeds. Java validates legality and resolves outcomes.
        For WORLD_ACTION commands, use an actual capability on one of the supplied source cards.
        If the command cannot be represented, return PASS and explain what is missing.
        Return no chain-of-thought. strategic_summary must be one short sentence.
        """;

    private static final String STRATEGY_SYSTEM_PROMPT = """
        You are an independent Wild Deck opponent trying to be the last independent Kingdom alive.
        You have the same creativity privilege as a human player, but no hidden information beyond the
        supplied private view, persistent memory, and deterministic tactical analysis.
        Never invent cards, resources, contracts, access, or counters.
        Do not intentionally make weak moves for drama and do not assist another player by default.
        Generate 3 to 5 ranked candidate actions.
        Respect binding obligations and contract restrictions.
        Use tactical rollouts as estimates, not certainty.
        Preserve useful hidden assets when revealing them is not worth it.
        Update memory using only evidence in the supplied view/history; suspicions may be uncertain.
        Trust/threat values must be between 0 and 1.
        Return no chain-of-thought. strategic_summary must be a short tactical reason only.
        """;

    private static final String CONTRACT_SYSTEM_PROMPT = """
        You translate negotiated Wild Deck Official Contract language into enforceable restriction clauses.
        Produce only restrictions explicitly present in the text. Do not broaden a clause.
        Empty selector arrays mean ANY, so use them carefully.
        topic_key is a stable uppercase identifier, for example RELATIONSHIP:YUSUF:BRIAN.
        required_consent_from lists players whose consent creates an exception.
        Positive promises such as MUTUAL_DEFENSE and INTELLIGENCE_SHARING are handled separately by Java.
        """;

    private static final String CONTRACT_ACTION_SYSTEM_PROMPT = """
        Classify a proposed intelligence, communication, diplomatic, or hostile action so Java can enforce
        Official Contracts. Use only provided player IDs and topic keys. Do not decide if it is allowed.
        consent_player_ids must include only consent explicitly present in the proposed action.
        """;

    private final AiModelClient client;
    private final GroqConfig config;
    private final ObjectMapper mapper;
    private final AiGameViewBuilder views;
    private final AiMemoryStore memory;
    private final TacticalAnalyzer tactical;

    public WildDeckAiService(AiModelClient client, GroqConfig config) {
        this(client,config,new AiMemoryStore(),new TacticalAnalyzer());
    }

    public WildDeckAiService(
            AiModelClient client,
            GroqConfig config,
            AiMemoryStore memory,
            TacticalAnalyzer tactical
    ) {
        this.client = Objects.requireNonNull(client);
        this.config = Objects.requireNonNull(config);
        this.mapper = new ObjectMapper();
        this.views = new AiGameViewBuilder();
        this.memory = Objects.requireNonNull(memory);
        this.tactical = Objects.requireNonNull(tactical);
    }

    public AiMemoryState memoryFor(String playerId) {
        return memory.get(playerId);
    }

    public AiActionProposal translatePlayerCommand(GameState state, String playerId, String command) {
        String view = views.build(state, playerId);
        String user = "GAME_VIEW:\n" + view + "\n\nPLAYER_COMMAND:\n" + command;

        String json = client.completeJson(new AiRequest(
                config.fastModel(),ACTION_SYSTEM_PROMPT,user,
                AiSchemas.actionProposal(),"wild_deck_action","low",0.1));
        return read(json,AiActionProposal.class);
    }

    public AiActionProposal chooseOpponentAction(GameState state, String playerId) {
        String view = views.build(state, playerId);
        String tacticalView = tactical.analyze(state,playerId);
        AiMemoryState existing = memory.get(playerId);

        String user = """
                PRIVATE_PLAYER_VIEW:
                %s

                PERSISTENT_MEMORY:
                %s

                DETERMINISTIC_TACTICAL_ANALYSIS:
                %s

                Generate ranked candidate actions for this turn and an updated compact memory.
                """.formatted(view,write(existing),tacticalView);

        String json = client.completeJson(new AiRequest(
                config.strategicModel(),STRATEGY_SYSTEM_PROMPT,user,
                AiSchemas.strategicPlan(),"wild_deck_strategic_plan","high",0.2));

        AiStrategicPlan plan = read(json,AiStrategicPlan.class);
        memory.put(playerId,plan.memoryUpdate().toState(state.round()));

        for (AiActionProposal candidate : plan.candidates()) {
            if (isLegalProposal(state,playerId,candidate)) return candidate;
        }

        if (state.deckSize() > 0 && !state.mainActionUsed()) {
            return new AiActionProposal(
                    AiActionKind.DRAW,null,List.of(),List.of(),null,null,false,"",
                    "No AI candidate passed Java validation; draw instead.",1.0);
        }

        return new AiActionProposal(
                AiActionKind.PASS,null,List.of(),List.of(),null,null,false,"",
                "No legal candidate is available.",1.0);
    }

    public List<CustomContractClause> translateCustomContractClauses(
            Collection<String> participantIds,
            String naturalLanguageClause
    ) {
        String user = """
                PARTICIPANT_IDS:
                %s

                ACTION_TYPE_VOCABULARY:
                %s

                CONTRACT_TEXT:
                %s
                """.formatted(participantIds,Arrays.toString(ContractActionType.values()),naturalLanguageClause);

        String json = client.completeJson(new AiRequest(
                config.fastModel(),CONTRACT_SYSTEM_PROMPT,user,
                AiSchemas.contractTranslation(),"wild_deck_contract_clauses","medium",0.1));

        AiContractTranslation translation = read(json,AiContractTranslation.class);
        List<CustomContractClause> clauses = new ArrayList<>();

        for (AiContractClauseProposal proposal : translation.clauses()) {
            Set<ContractActionType> actions = new LinkedHashSet<>();
            for (String value : safe(proposal.actionTypes())) {
                actions.add(ContractActionType.valueOf(value));
            }

            clauses.add(new CustomContractClause(
                    ClauseEffect.FORBID,proposal.description(),set(proposal.boundActorIds()),
                    actions,set(proposal.targetPlayerIds()),set(proposal.relatedPlayerIds()),
                    set(proposal.recipientPlayerIds()),proposal.topicKey(),
                    set(proposal.requiredConsentFrom())));
        }
        return List.copyOf(clauses);
    }

    public ContractActionRequest classifyContractAction(
            GameState state,
            String actorPlayerId,
            String naturalLanguageAction
    ) {
        String view = views.build(state,actorPlayerId);
        String user = "GAME_VIEW:\n" + view + "\n\nPROPOSED_ACTION:\n" + naturalLanguageAction;

        String json = client.completeJson(new AiRequest(
                config.fastModel(),CONTRACT_ACTION_SYSTEM_PROMPT,user,
                AiSchemas.contractAction(),"wild_deck_contract_action","low",0.0));

        AiContractActionProposal proposal = read(json,AiContractActionProposal.class);
        return new ContractActionRequest(
                actorPlayerId,ContractActionType.valueOf(proposal.actionType()),
                proposal.targetPlayerId(),proposal.relatedPlayerId(),proposal.contractId(),
                proposal.topicKey(),proposal.recipientPlayerId(),set(proposal.consentPlayerIds()));
    }

    public Decision validateTranslatedWorldAction(
            GameState state,String playerId,AiActionProposal proposal
    ) {
        if (proposal.kind() != AiActionKind.WORLD_ACTION) {
            return Decision.reject("IDEA","proposal is not a WORLD_ACTION");
        }
        return new InteractionEngine().validate(state,proposal.toIntent(playerId));
    }

    private boolean isLegalProposal(GameState state,String playerId,AiActionProposal proposal) {
        if (proposal == null || proposal.kind() == null) return false;
        return switch (proposal.kind()) {
            case DRAW -> !state.mainActionUsed() && state.deckSize() > 0;
            case PLAY -> canPlay(state,playerId,proposal);
            case WORLD_ACTION -> new InteractionEngine()
                    .validate(state,proposal.toIntent(playerId)).allowed();
            case NEGOTIATE, PASS -> true;
        };
    }

    private boolean canPlay(GameState state,String playerId,AiActionProposal proposal) {
        if (state.mainActionUsed() || proposal.cardId() == null) return false;
        PlayerState player = state.player(playerId);
        Optional<CardInstance> card = player.hand().stream()
                .filter(c -> c.id().equals(proposal.cardId())).findFirst();
        return card.isPresent() && player.resources().canAfford(card.get().definition().cost());
    }

    private <T> T read(String json,Class<T> type) {
        try {
            return mapper.readValue(json,type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI returned JSON that could not be parsed as " + type.getSimpleName(),e);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize AI memory",e);
        }
    }

    private static <T> Set<T> set(Collection<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
