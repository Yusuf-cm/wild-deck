package com.wilddeck.engine;

import java.util.*;

public final class CardInstance {
    private final String id = UUID.randomUUID().toString();
    private final CardDefinition definition;
    private String ownerId;
    private String controllerId;
    private Zone zone;
    private Visibility visibility;
    private final Set<String> states = new LinkedHashSet<>();
    private int damage;

    public CardInstance(CardDefinition definition, String ownerId, Zone zone, Visibility visibility) {
        this.definition = Objects.requireNonNull(definition);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.controllerId = ownerId;
        this.zone = Objects.requireNonNull(zone);
        this.visibility = Objects.requireNonNull(visibility);
    }

    public String id() { return id; }
    public CardDefinition definition() { return definition; }
    public String ownerId() { return ownerId; }
    public String controllerId() { return controllerId; }
    public Zone zone() { return zone; }
    public Visibility visibility() { return visibility; }
    public Set<String> states() { return Set.copyOf(states); }
    public int damage() { return damage; }

    public void setOwnerId(String value) { ownerId = Objects.requireNonNull(value); }
    public void setControllerId(String value) { controllerId = Objects.requireNonNull(value); }
    public void setZone(Zone value) { zone = Objects.requireNonNull(value); }
    public void setVisibility(Visibility value) { visibility = Objects.requireNonNull(value); }
    public void addState(String value) { states.add(value.trim().toUpperCase()); }
    public void removeState(String value) { states.remove(value.trim().toUpperCase()); }
    public void addDamage(int amount) { damage += Math.max(0, amount); }
    public void healDamage(int amount) { damage = Math.max(0, damage - Math.max(0, amount)); }
}
