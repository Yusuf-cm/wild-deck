package com.wilddeck.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroqClientTest {

    @Test
    void jsonObjectFallbackPromptExplicitlyRequestsJson() {
        String prompt = GroqClient.jsonObjectSystemPrompt(
                "Choose the strongest legal move."
        );

        assertTrue(prompt.toLowerCase().contains("json"));
        assertTrue(prompt.contains("valid JSON object"));
    }
}
