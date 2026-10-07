package com.wilddeck.engine;

import java.util.*;

public final class PlayerState {
    private final String id;
    private final String name;
    private final ResourcePool resources = new ResourcePool();
    private final List<CardInstance> hand = new ArrayList<>();
    private final Map<String,CardInstance> kingdom = new LinkedHashMap<>();
    private final Set<String> knownCards = new LinkedHashSet<>();

    public PlayerState(String id, String name) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
    }

    public String id() { return id; }
    public String name() { return name; }
    public ResourcePool resources() { return resources; }
    public List<CardInstance> hand() { return List.copyOf(hand); }
    public Collection<CardInstance> kingdom() { return List.copyOf(kingdom.values()); }

    public void addToHand(CardInstance card) {
        card.setOwnerId(id);
        card.setControllerId(id);
        card.setZone(Zone.HAND);
        card.setVisibility(Visibility.HIDDEN);
        hand.add(card);
        knownCards.add(card.id());
    }

    public CardInstance removeFromHand(String cardId) {
        for (int i=0;i<hand.size();i++) {
            if (hand.get(i).id().equals(cardId)) return hand.remove(i);
        }
        return null;
    }

    public Optional<CardInstance> find(String cardId) {
        return hand.stream().filter(c -> c.id().equals(cardId)).findFirst()
                .or(() -> Optional.ofNullable(kingdom.get(cardId)));
    }

    public void deploy(CardInstance card, boolean hidden) {
        card.setZone(Zone.KINGDOM);
        card.setVisibility(hidden ? Visibility.HIDDEN : Visibility.PUBLIC);
        kingdom.put(card.id(), card);
        knownCards.add(card.id());
    }

    public boolean knows(String cardId) { return knownCards.contains(cardId); }
    public void reveal(String cardId) { knownCards.add(cardId); }
}
