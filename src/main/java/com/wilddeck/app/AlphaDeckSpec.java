package com.wilddeck.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

public record AlphaDeckSpec(@JsonProperty("cards") List<CardSpec> cards) {
    public record CardSpec(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("category") String category,
            @JsonProperty("properties") Set<String> properties,
            @JsonProperty("capabilities") Set<String> capabilities,
            @JsonProperty("strength") Integer strength,
            @JsonProperty("copies") int copies,
            @JsonProperty("cost") Map<String,Integer> cost
    ) {
        public CardSpec {
            properties = properties == null ? Set.of() : Set.copyOf(properties);
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            cost = cost == null ? Map.of() : Map.copyOf(cost);
            if (copies < 1) throw new IllegalArgumentException("copies must be >= 1");
        }
    }
}
