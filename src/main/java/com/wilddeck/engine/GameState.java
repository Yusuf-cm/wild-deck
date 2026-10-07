package com.wilddeck.engine;

import java.util.*;

public final class GameState {
    private final GameRules rules;
    private final Map<String,PlayerState> players = new LinkedHashMap<>();
    private final List<Contract> contracts = new ArrayList<>();
    private final Deque<CardInstance> deck = new ArrayDeque<>();
    private String activePlayerId;
    private boolean mainActionUsed;
    private int round = 1;

    public GameState(GameRules rules, Collection<PlayerState> players, Collection<CardInstance> deck, String firstPlayerId) {
        this.rules = Objects.requireNonNull(rules);
        for (PlayerState player : players) this.players.put(player.id(), player);
        this.deck.addAll(deck);
        this.activePlayerId = firstPlayerId;
    }

    public GameRules rules() { return rules; }
    public int round() { return round; }
    public String activePlayerId() { return activePlayerId; }
    public boolean mainActionUsed() { return mainActionUsed; }
    public Collection<PlayerState> players() { return List.copyOf(players.values()); }
    public List<Contract> contracts() { return List.copyOf(contracts); }

    public PlayerState player(String id) {
        PlayerState player = players.get(id);
        if (player == null) throw new IllegalArgumentException("unknown player " + id);
        return player;
    }

    public Optional<CardInstance> findCard(String id) {
        for (PlayerState player : players.values()) {
            Optional<CardInstance> card = player.find(id);
            if (card.isPresent()) return card;
        }
        return Optional.empty();
    }

    public CardInstance drawTop() {
        CardInstance card = deck.pollFirst();
        if (card == null) throw new IllegalStateException("deck is empty");
        return card;
    }

    public void beginTurn(String playerId) {
        activePlayerId = playerId;
        mainActionUsed = false;
    }

    public void consumeMainAction() {
        if (mainActionUsed) throw new IllegalStateException("main action already used");
        mainActionUsed = true;
    }

    public void nextRound() { round++; }
    public void addContract(Contract contract) { contracts.add(contract); }

    public boolean blocksHostility(String attackerId, String targetId) {
        return contracts.stream().anyMatch(c ->
                c.binds(attackerId, targetId, ContractTerm.NON_AGGRESSION, round));
    }
}
