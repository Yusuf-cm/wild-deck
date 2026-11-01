package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldGeneratorTest {
    @Test void seededGenerationIsReproducibleAndConnected() {
        WorldGenerator generator = new WorldGenerator();
        List<String> players = List.of("player", "asha", "brian");
        WorldMap a = generator.generate(42, players);
        WorldMap b = generator.generate(42, players);
        assertEquals(a.locations().size(), b.locations().size());
        for (WorldLocation location : a.locations()) {
            assertEquals(a.neighbors(location.id()), b.neighbors(location.id()));
            for (WorldLocation other : a.locations())
                assertTrue(a.reachable(location.id(), other.id()));
        }
        assertTrue(a.location("forest").orElseThrow().hasProperty("FLAMMABLE"));
        assertTrue(a.location("kingdom:player").isPresent());
    }

    @Test void mapRejectsInvalidConnectionsAndDuplicateLocations() {
        WorldLocation a = new WorldLocation("a", "A", Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> new WorldMap(List.of(a), Map.of("a", Set.of("missing"))));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldMap(List.of(a, a), Map.of()));
    }

    @Test void generationRejectsDuplicatePlayers() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldGenerator().generate(1, List.of("a", "a")));
    }
}
