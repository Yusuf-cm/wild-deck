package com.wilddeck.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record AiRequest(
        String model,
        String systemPrompt,
        String userPrompt,
        JsonNode jsonSchema,
        String schemaName,
        String reasoningEffort,
        double temperature
) {}
