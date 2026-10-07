package com.wilddeck.ai;

public record GroqConfig(
        String apiKey,
        String baseUrl,
        String fastModel,
        String strategicModel
) {
    public static GroqConfig fromEnvironment() {
        String key = System.getenv("GROQ_API_KEY");
        String base = env("GROQ_BASE_URL", "https://api.groq.com/openai/v1");
        String fast = env("GROQ_FAST_MODEL", "openai/gpt-oss-20b");
        String strategic = env("GROQ_STRATEGIC_MODEL", "openai/gpt-oss-120b");
        return new GroqConfig(key, base, fast, strategic);
    }

    public void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is not configured");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
