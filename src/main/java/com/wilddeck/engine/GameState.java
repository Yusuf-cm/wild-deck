package com.wilddeck.engine;

import java.util.*;

public final class GameState {
    private final GameRules rules;
    private final Map<String,PlayerState> players = new LinkedHashMap<>();
    private final List<Contract> contracts = new ArrayList<>();
    private final Map<String,Occupation> occupations = new LinkedHashMap<>();
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
    public Collection<Occupation> occupations() { return List.copyOf(occupations.values()); }

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

    public void addOccupation(Occupation occupation) {
        occupations.put(occupation.id(), occupation);
    }

    public Optional<Occupation> occupation(String id) {
        return Optional.ofNullable(occupations.get(id));
    }

    public boolean hasCompletedOccupation(String occupierId, String defenderId) {
        return occupations.values().stream().anyMatch(o ->
                o.occupierPlayerId().equals(occupierId)
                        && o.defenderPlayerId().equals(defenderId)
                        && o.status() == OccupationStatus.COMPLETED);
    }

    public boolean blocksHostility(String attackerId, String targetId) {
        PlayerState attacker = player(attackerId);

        boolean vassalDutyBlocksAttack = attacker.kingdomStatus() == KingdomStatus.VASSAL
                && attacker.overlordId().map(targetId::equals).orElse(false);

        return vassalDutyBlocksAttack || contracts.stream().anyMatch(c ->
                c.binds(attackerId, targetId, ContractTerm.NON_AGGRESSION, round));
    }

    public Optional<PlayerState> winner() {
        List<PlayerState> independent = players.values().stream()
                .filter(PlayerState::isIndependent)
                .toList();
        if (independent.size() != 1) return Optional.empty();

        boolean everyOtherKingdomSubordinateOrEliminated = players.values().stream()
                .filter(p -> !p.id().equals(independent.get(0).id()))
                .allMatch(p -> p.kingdomStatus() != KingdomStatus.INDEPENDENT);

        return everyOtherKingdomSubordinateOrEliminated
                ? Optional.of(independent.get(0))
                : Optional.empty();
    }
}
