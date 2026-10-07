package com.wilddeck.engine;

import java.util.*;

public final class PlayerState {
    private final String id;
    private final String name;
    private final ResourcePool resources = new ResourcePool();
    private final List<CardInstance> hand = new ArrayList<>();
    private final Map<String,CardInstance> kingdom = new LinkedHashMap<>();
    private final Map<String,CardInstance> graveyard = new LinkedHashMap<>();
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
    public Collection<CardInstance> graveyard() { return List.copyOf(graveyard.values()); }

    public void addToHand(CardInstance card) {
        removeFromWorld(card.id());
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
        Optional<CardInstance> inHand = hand.stream().filter(c -> c.id().equals(cardId)).findFirst();
        if (inHand.isPresent()) return inHand;
        CardInstance deployed = kingdom.get(cardId);
        if (deployed != null) return Optional.of(deployed);
        return Optional.ofNullable(graveyard.get(cardId));
    }

    public void deploy(CardInstance card, boolean hidden) {
        removeFromWorld(card.id());
        card.setZone(Zone.KINGDOM);
        card.setControllerId(id);
        card.setVisibility(hidden ? Visibility.HIDDEN : Visibility.PUBLIC);
        kingdom.put(card.id(), card);
        knownCards.add(card.id());
    }

    public void moveToGraveyard(CardInstance card) {
        removeFromWorld(card.id());
        card.setZone(Zone.GRAVEYARD);
        card.setVisibility(Visibility.PUBLIC);
        graveyard.put(card.id(), card);
        knownCards.add(card.id());
    }

    public CardInstance removeFromKingdom(String cardId) {
        return kingdom.remove(cardId);
    }

    public CardInstance removeFromGraveyard(String cardId) {
        return graveyard.remove(cardId);
    }

    public boolean knows(String cardId) { return knownCards.contains(cardId); }
    public void reveal(String cardId) { knownCards.add(cardId); }

    private void removeFromWorld(String cardId) {
        hand.removeIf(c -> c.id().equals(cardId));
        kingdom.remove(cardId);
        graveyard.remove(cardId);
    }
}
