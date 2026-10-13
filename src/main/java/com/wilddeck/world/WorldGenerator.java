package com.wilddeck.world;

import java.util.*;

/** Small deterministic geography for quick matches. Does not mutate game state. */
public final class WorldGenerator {
    private static final List<WorldLocation> SHARED = List.of(
            new WorldLocation("forest", "Ancient Forest", Set.of("FOREST", "FLAMMABLE", "CONCEALMENT")),
            new WorldLocation("river", "River Crossing", Set.of("WATER", "CROSSING")),
            new WorldLocation("ruins", "Abandoned Fortress", Set.of("STONE", "RUINS", "SHELTER")),
            new WorldLocation("pass", "Mountain Pass", Set.of("ROCK", "ELEVATED", "CHOKEPOINT"))
    );

    public WorldMap generate(long seed, Collection<String> playerIds) {
        if (playerIds.size() < 2) throw new IllegalArgumentException("at least two kingdoms required");
        List<String> ids = List.copyOf(playerIds);
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("duplicate player id");
        List<WorldLocation> locations = new ArrayList<>(SHARED);
        Map<String, Set<String>> links = new LinkedHashMap<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || SHARED.stream().anyMatch(l -> l.id().equals("kingdom:" + id)))
                throw new IllegalArgumentException("invalid player id");
            locations.add(new WorldLocation("kingdom:" + id, id + "'s Kingdom", Set.of("KINGDOM")));
        }
        // Seed changes the approach each kingdom gets, without isolating anyone.
        Random random = new Random(seed);
        List<String> shared = new ArrayList<>(List.of("forest", "river", "ruins", "pass"));
        Collections.shuffle(shared, random);
        connect(links, shared.get(0), shared.get(1));
        connect(links, shared.get(1), shared.get(2));
        connect(links, shared.get(2), shared.get(3));
        connect(links, shared.get(3), shared.get(0));
        for (int i = 0; i < ids.size(); i++) {
            String kingdom = "kingdom:" + ids.get(i);
            connect(links, kingdom, shared.get(i % shared.size()));
        }
        return new WorldMap(locations, links);
    }

    private static void connect(Map<String, Set<String>> links, String a, String b) {
        links.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
        links.computeIfAbsent(b, ignored -> new LinkedHashSet<>()).add(a);
    }
}
