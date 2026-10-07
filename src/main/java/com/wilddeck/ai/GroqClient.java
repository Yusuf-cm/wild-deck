package com.wilddeck.ai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/**
 * Minimal Groq OpenAI-compatible Chat Completions client.
 *
 * The model is never authoritative over game state. It only returns structured
 * proposals that Java validates before any mutation occurs.
 */
public final class GroqClient implements AiModelClient {
    private final GroqConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GroqClient(GroqConfig config) {
        this(config,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                new ObjectMapper());
    }

    GroqClient(GroqConfig config, HttpClient http, ObjectMapper mapper) {
        this.config = config;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String completeJson(AiRequest request) {
        config.requireApiKey();

        HttpResponse<String> first = send(request, true);
        if (first.statusCode() >= 200 && first.statusCode() < 300) {
            return extractContent(first.body());
        }

        // Strict structured-output support can vary by model/schema. A 400 gets
        // one deterministic fallback to JSON Object Mode.
        if (first.statusCode() == 400) {
            HttpResponse<String> fallback = send(request, false);
            if (fallback.statusCode() >= 200 && fallback.statusCode() < 300) {
                return extractContent(fallback.body());
            }
            throw combinedApiError(first,fallback);
        }

        throw apiError(first);
    }

    private HttpResponse<String> send(AiRequest request, boolean strictSchema) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", request.model());
            body.put("temperature", request.temperature());
            if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
                body.put("reasoning_effort", request.reasoningEffort());
            }

            ArrayNode messages = body.putArray("messages");
            String systemPrompt = strictSchema
                    ? request.systemPrompt()
                    : jsonObjectSystemPrompt(request.systemPrompt(),request.jsonSchema().toString());
            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt);
            messages.addObject()
                    .put("role", "user")
                    .put("content", request.userPrompt());

            ObjectNode responseFormat = body.putObject("response_format");
            if (strictSchema) {
                responseFormat.put("type", "json_schema");
                ObjectNode jsonSchema = responseFormat.putObject("json_schema");
                jsonSchema.put("name", request.schemaName());
                jsonSchema.put("strict", true);
                jsonSchema.set("schema", request.jsonSchema());
            } else {
                responseFormat.put("type", "json_object");
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(config.baseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            return http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize Groq request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Groq request interrupted", e);
        }
    }

    private String extractContent(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new IllegalStateException("Groq response missing choices[0].message.content");
            }
            return content.asText();
        } catch (IOException e) {
            throw new IllegalStateException("invalid Groq response JSON", e);
        }
    }

    private IllegalStateException apiError(HttpResponse<String> response) {
        return new IllegalStateException(
                "Groq API error " + response.statusCode() + ": " + response.body());
    }

    static String jsonObjectSystemPrompt(String original,String schema) {
        String prompt = original == null ? "" : original;
        String expected = schema == null ? "{}" : schema;
        return prompt
                + "\nReturn only a valid JSON object matching this exact JSON schema:"
                + "\n" + expected
                + "\nUse empty strings for unused scalar fields when the schema requires strings.";
    }

    private IllegalStateException combinedApiError(
            HttpResponse<String> strict,
            HttpResponse<String> fallback
    ) {
        return new IllegalStateException(
                "Groq strict JSON-schema request failed "
                        + strict.statusCode() + ": " + strict.body()
                        + " | JSON-object fallback failed "
                        + fallback.statusCode() + ": " + fallback.body());
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
