package com.wilddeck.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wilddeck.engine.*;

import java.io.InputStream;
import java.util.*;

public final class AlphaDeckFactory {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<CardInstance> create(long seed) {
        AlphaDeckSpec spec = load();
        List<CardInstance> deck = new ArrayList<>();

        for (AlphaDeckSpec.CardSpec card : spec.cards()) {
            Map<ResourceType,Integer> cost = new EnumMap<>(ResourceType.class);
            for (Map.Entry<String,Integer> entry : card.cost().entrySet()) {
                cost.put(ResourceType.valueOf(entry.getKey()),entry.getValue());
            }

            CardDefinition definition = new CardDefinition(
                    card.id(),card.name(),card.category(),card.properties(),
                    card.capabilities(),card.strength(),cost,"alpha-playtest-deck-v1"
            );

            for (int i=0;i<card.copies();i++) {
                deck.add(new CardInstance(definition,"deck",Zone.DECK,Visibility.HIDDEN));
            }
        }

        Collections.shuffle(deck,new Random(seed));
        return List.copyOf(deck);
    }

    private AlphaDeckSpec load() {
        try (InputStream in = AlphaDeckFactory.class.getResourceAsStream(
                "/data/alpha-playtest-deck-v1.json")) {
            if (in == null) throw new IllegalStateException("alpha deck resource not found");
            return mapper.readValue(in,AlphaDeckSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load alpha deck",e);
        }
    }
}
