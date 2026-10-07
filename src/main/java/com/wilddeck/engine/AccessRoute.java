package com.wilddeck.engine;

import java.util.*;

public final class AccessRoute {
    private final String id = UUID.randomUUID().toString();
    private final String ownerPlayerId;
    private final String fromPlayerId;
    private final String toPlayerId;
    private final AccessRouteType type;
    private final Set<String> supportCardIds;
    private final Visibility visibility;
    private boolean active = true;

    public AccessRoute(
            String ownerPlayerId,
            String fromPlayerId,
            String toPlayerId,
            AccessRouteType type,
            Collection<String> supportCardIds,
            Visibility visibility
    ) {
        this.ownerPlayerId = Objects.requireNonNull(ownerPlayerId);
        this.fromPlayerId = Objects.requireNonNull(fromPlayerId);
        this.toPlayerId = Objects.requireNonNull(toPlayerId);
        this.type = Objects.requireNonNull(type);
        this.supportCardIds = Set.copyOf(supportCardIds);
        this.visibility = Objects.requireNonNull(visibility);
        if (fromPlayerId.equals(toPlayerId)) {
            throw new IllegalArgumentException("route endpoints must differ");
        }
        if (this.supportCardIds.isEmpty()) {
            throw new IllegalArgumentException("route requires at least one supporting card");
        }
    }

    public String id() { return id; }
    public String ownerPlayerId() { return ownerPlayerId; }
    public String fromPlayerId() { return fromPlayerId; }
    public String toPlayerId() { return toPlayerId; }
    public AccessRouteType type() { return type; }
    public Set<String> supportCardIds() { return supportCardIds; }
    public Visibility visibility() { return visibility; }
    public boolean active() { return active; }
    public void deactivate() { active = false; }

    public boolean connects(String actorId, String targetId) {
        return active && ownerPlayerId.equals(actorId)
                && fromPlayerId.equals(actorId) && toPlayerId.equals(targetId);
    }
}
