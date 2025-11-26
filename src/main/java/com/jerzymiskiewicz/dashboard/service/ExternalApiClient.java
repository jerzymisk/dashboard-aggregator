package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Production implementation of {@link ExternalApi}.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Perform non-blocking HTTP calls to external services</li>
 *   <li>Parse JSON responses into {@link JsonNode}</li>
 *   <li>Validate presence of minimal required fields</li>
 *   <li>Convert all failures into exceptionally completed {@link CompletableFuture}s</li>
 * </ul>
 */
public record ExternalApiClient(HttpClient httpClient, ObjectMapper objectMapper) implements ExternalApi {

    /** Open-Meteo API — current weather for Warsaw (current_weather node). */
    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=51.107883&longitude=17.038538&current_weather=true";

    /** Random joke API, returns a JSON object with the 'value' field. */
    private static final String FACT_URL =
            "https://uselessfacts.jsph.pl/api/v2/facts/random";

    /** Public IP provider, returns {"ip": "..."} JSON. */
    private static final String IP_URL = "https://api.ipify.org?format=json";

    /** Pre-parsed URIs to avoid repeating URI creation. */
    private static final URI WEATHER_URI = URI.create(WEATHER_URL);
    private static final URI FACT_URI    = URI.create(FACT_URL);
    private static final URI IP_URI      = URI.create(IP_URL);

    /** Timeout applied to all outbound HTTP requests. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Template for missing-field error messages. */
    private static final String ERR_MISSING_FIELD = "Missing required JSON field: %s";

    /**
     * Convenience constructor that builds a default {@link HttpClient}
     * and {@link ObjectMapper}. This is what production code uses.
     */
    public ExternalApiClient() {
        this(HttpClient.newBuilder()
                        .connectTimeout(REQUEST_TIMEOUT)
                        .build(),
                new ObjectMapper());
    }

    /**
     * Canonical record constructor with null-safety.
     * <p>
     * This is used both by the default constructor and by tests
     * that want to inject their own HttpClient / ObjectMapper.
     */
    public ExternalApiClient {
        Objects.requireNonNull(httpClient, "httpClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    // ========================================================================
    // ExternalApi implementation
    // ========================================================================

    /**
     * Fetches current weather from Open-Meteo and returns the "current_weather" node.
     * <p>
     * Example response shape:
     * <pre>
     * {
     *   "latitude": ...,
     *   "longitude": ...,
     *   "current_weather": {
     *     "time": "...",
     *     "temperature": 1.0,
     *     "windspeed": 11.2,
     *     ...
     *   }
     * }
     * </pre>
     */
    @Override
    public CompletableFuture<JsonNode> fetchWeather() {
        return sendJsonRequest(WEATHER_URI)
                .thenApply(json -> extractRequiredNode(json, "current_weather"));
    }

    /**
     * Fetches a random joke and validates that the "value" field is present.
     * The whole JSON object is returned to the caller.
     */
    @Override
    public CompletableFuture<JsonNode> fetchRandomFact() {
        return sendJsonRequest(FACT_URI)
                .thenApply(json -> {
                    String fact = json.get("text").asText();
                    return objectMapper.createObjectNode().put("fact", fact);
                });
    }

    /**
     * Fetches the public IP and validates the "ip" field.
     * The whole JSON object is returned to the caller.
     */
    @Override
    public CompletableFuture<JsonNode> fetchPublicIp() {
        return sendJsonRequest(IP_URI)
                .thenApply(ensureHasField("ip"));
    }

    // ========================================================================
    // HTTP + JSON helpers
    // ========================================================================

    /**
     * Sends an asynchronous HTTP GET request and parses the response body as JSON.
     * <p>
     * Behaviour:
     * <ul>
     *   <li>If status is non-2xx → returns a failed future</li>
     *   <li>If JSON parsing fails → returns a failed future</li>
     *   <li>Otherwise → returns a completed future with parsed {@link JsonNode}</li>
     * </ul>
     */
    private CompletableFuture<JsonNode> sendJsonRequest(URI uri) {
        HttpRequest request = buildGetRequest(uri);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (!is2xx(status)) {
                        return failed("Non-2xx response: " + status);
                    }
                    return parseJson(response.body());
                });
    }

    /**
     * Parses raw JSON into a {@link JsonNode}.
     * <p>
     * On parse errors the returned future is completed exceptionally.
     */
    private CompletableFuture<JsonNode> parseJson(String rawBody) {
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            return CompletableFuture.completedFuture(node);
        } catch (IOException e) {
            return failed("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // JSON validation helpers
    // ========================================================================

    /**
     * Ensures that the JSON object contains a non-null field.
     * <p>
     * If the field is missing or null, throws a {@link CompletionException}
     * wrapping an {@link IllegalStateException}. This is intentional so that
     * the exception correctly propagates through {@link CompletableFuture}.
     */
    private void ensureFieldPresent(JsonNode json, String field) {
        if (!json.hasNonNull(field)) {
            throw new CompletionException(
                    new IllegalStateException(String.format(ERR_MISSING_FIELD, field))
            );
        }
    }

    /**
     * Returns a function suitable for usage in {@code thenApply(...)}.
     * <p>
     * It validates that the given field is present and non-null, and then
     * returns the original JSON unchanged.
     */
    private Function<JsonNode, JsonNode> ensureHasField(String field) {
        return json -> {
            ensureFieldPresent(json, field);
            return json;
        };
    }

    /**
     * Validates that the given field is present and non-null,
     * then returns the nested field node.
     */
    private JsonNode extractRequiredNode(JsonNode json, String field) {
        ensureFieldPresent(json, field);
        return json.get(field);
    }

    // ========================================================================
    // Utility helpers
    // ========================================================================

    /**
     * Creates a failed {@link CompletableFuture} with a generic {@link RuntimeException}.
     */
    private static <T> CompletableFuture<T> failed(String message) {
        return CompletableFuture.failedFuture(new RuntimeException(message));
    }

    /**
     * Creates a failed {@link CompletableFuture} with a {@link RuntimeException}
     * that wraps the original cause.
     */
    private static <T> CompletableFuture<T> failed(String message, Throwable cause) {
        return CompletableFuture.failedFuture(new RuntimeException(message, cause));
    }

    /**
     * Builds a GET {@link HttpRequest} with a standard timeout.
     * <p>
     * Any shared headers (e.g. API keys) can be added here in the future.
     */
    private static HttpRequest buildGetRequest(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
    }

    /**
     * Simple helper to check if a status code is in the 2xx range.
     */
    private static boolean is2xx(int status) {
        return status >= 200 && status < 300;
    }
}