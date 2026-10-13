package com.wilddeck.world;

import java.util.*;

/** Undirected location graph. This is geography, not card access authorization. */
public final class WorldMap {
    private final Map<String, WorldLocation> locations;
    private final Map<String, Set<String>> neighbors;

    public WorldMap(Collection<WorldLocation> locations, Map<String, Set<String>> connections) {
        Map<String, WorldLocation> byId = new LinkedHashMap<>();
        for (WorldLocation location : locations) {
            if (byId.putIfAbsent(location.id(), location) != null)
                throw new IllegalArgumentException("duplicate location: " + location.id());
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (String id : byId.keySet()) adjacency.put(id, new LinkedHashSet<>());
        for (var entry : connections.entrySet()) {
            if (!byId.containsKey(entry.getKey())) throw new IllegalArgumentException("unknown location");
            for (String neighbor : entry.getValue()) {
                if (!byId.containsKey(neighbor) || entry.getKey().equals(neighbor))
                    throw new IllegalArgumentException("invalid connection");
                adjacency.get(entry.getKey()).add(neighbor);
                adjacency.get(neighbor).add(entry.getKey());
            }
        }
        this.locations = Map.copyOf(byId);
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        adjacency.forEach((id, links) -> frozen.put(id, Set.copyOf(links)));
        this.neighbors = Map.copyOf(frozen);
    }

    public Collection<WorldLocation> locations() { return locations.values(); }
    public Optional<WorldLocation> location(String id) { return Optional.ofNullable(locations.get(id)); }
    public Set<String> neighbors(String id) {
        if (!neighbors.containsKey(id)) throw new IllegalArgumentException("unknown location: " + id);
        return neighbors.get(id);
    }

    public boolean connected(String from, String to) {
        return neighbors(from).contains(to);
    }

    public boolean reachable(String from, String to) {
        if (!locations.containsKey(from) || !locations.containsKey(to)) return false;
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (current.equals(to)) return true;
            queue.addAll(neighbors(current));
        }
        return false;
    }
}
