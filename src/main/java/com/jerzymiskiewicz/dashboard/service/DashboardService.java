package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * High–level service that orchestrates:
 * <ul>
 *   <li>Reading from Redis cache</li>
 *   <li>Calling external APIs (weather, fact, IP)</li>
 *   <li>Building the aggregated JSON dashboard</li>
 *   <li>Storing fresh result back into Redis</li>
 * </ul>
 *
 * Behaviour:
 * <ul>
 *   <li>On cache HIT → returns cached JSON, external APIs are NOT called</li>
 *   <li>On cache MISS → calls all APIs in parallel and builds JSON</li>
 *   <li>If one of the APIs fails → dashboard is still returned,
 *       but only successful parts are included, and error fields are added</li>
 *   <li>Redis is updated ONLY if all APIs succeed</li>
 * </ul>
 */
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** Redis key under which the last successful dashboard JSON is stored. */
    private static final String CACHE_KEY = "dashboard:lastSuccess";

    /** TTL for cached dashboard (in seconds). */
    private static final long CACHE_TTL_SECONDS = 60L;

    private final ExternalApi externalApi;
    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;

    /**
     * Convenience constructor that creates an {@link ObjectMapper} internally.
     */
    public DashboardService(ExternalApi externalApi, RedisCache redisCache) {
        this(externalApi, redisCache, new ObjectMapper());
    }

    /**
     * Full constructor used both in production and tests.
     */
    public DashboardService(ExternalApi externalApi,
                            RedisCache redisCache,
                            ObjectMapper objectMapper) {
        this.externalApi = Objects.requireNonNull(externalApi, "externalApi must not be null");
        this.redisCache = Objects.requireNonNull(redisCache, "redisCache must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Public entrypoint for controllers/handlers.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Try to read cached JSON from Redis</li>
     *   <li>On cache HIT → return cached value</li>
     *   <li>On cache MISS → build fresh dashboard from external APIs</li>
     * </ol>
     */
    public CompletableFuture<String> getDashboardJson() {
        return redisCache.get(CACHE_KEY)
                .thenCompose(optionalJson -> {
                    if (optionalJson.isPresent()) {
                        log.info("Cache HIT for key '{}'", CACHE_KEY);
                        return CompletableFuture.completedFuture(optionalJson.get());
                    }
                    log.info("Cache MISS for key '{}'", CACHE_KEY);
                    return buildFreshDashboardJson();
                });
    }

    /**
     * Builds a fresh dashboard JSON by calling all external APIs in parallel.
     * <p>
     * Graceful degradation:
     * <ul>
     *   <li>If one API fails, its value becomes {@code null}</li>
     *   <li>Successful parts are still included in the response JSON</li>
     *   <li>For failed parts, an <code>"*_error": "unavailable"</code> field is added</li>
     *   <li>Redis cache is updated only if ALL three calls succeed</li>
     * </ul>
     */
    private CompletableFuture<String> buildFreshDashboardJson() {

        // Call external APIs in parallel, but wrap each with safeFetch(..) so that
        // a single failure does NOT fail the entire dashboard.
        CompletableFuture<JsonNode> weatherFuture =
                safeFetch("weather", externalApi.fetchWeather());
        CompletableFuture<JsonNode> factFuture =
                safeFetch("fact", externalApi.fetchRandomFact());
        CompletableFuture<JsonNode> ipFuture =
                safeFetch("ip", externalApi.fetchPublicIp());

        // Wait until all three futures are completed (successfully or exceptionally),
        // then build the final JSON from whatever we have.
        return CompletableFuture
                .allOf(weatherFuture, factFuture, ipFuture)
                .thenApply(ignored -> {
                    JsonNode weather = weatherFuture.join();
                    JsonNode fact = factFuture.join();
                    JsonNode ip = ipFuture.join();

                    ObjectNode root = objectMapper.createObjectNode();

                    // Weather section
                    if (weather != null) {
                        root.set("weather", weather);
                    } else {
                        root.put("weather_error", "unavailable");
                    }

                    // Fact section
                    if (fact != null) {
                        root.set("fact", fact);
                    } else {
                        root.put("fact_error", "unavailable");
                    }

                    // IP section
                    if (ip != null) {
                        root.set("ip", ip);
                    } else {
                        root.put("ip_error", "unavailable");
                    }

                    final String json;
                    try {
                        json = objectMapper.writeValueAsString(root);
                    } catch (Exception e) {
                        // If we cannot serialize JSON, fail the future.
                        throw new CompletionException("Failed to serialize dashboard JSON", e);
                    }

                    // Only cache if ALL external calls succeeded.
                    if (weather != null && fact != null && ip != null) {
                        redisCache.save(CACHE_KEY, json, CACHE_TTL_SECONDS)
                                .exceptionally(ex -> {
                                    log.warn("Failed to save dashboard to Redis: {}", ex.toString());
                                    return null;
                                });
                    } else {
                        log.warn("Skipping cache update: at least one external API failed");
                    }

                    return json;
                });
    }

    /**
     * Wraps external API calls so that failures do NOT break the entire dashboard.
     * <p>
     * Behaviour:
     * <ul>
     *   <li>If the original future completes normally → value is propagated</li>
     *   <li>If it completes exceptionally → we log a warning and return {@code null}</li>
     * </ul>
     *
     * @param name     logical name of the API ("weather", "fact", "ip") used only for logging
     * @param original original future returned by {@link ExternalApi}
     */
    private CompletableFuture<JsonNode> safeFetch(String name,
                                                  CompletableFuture<JsonNode> original) {
        return original.exceptionally(ex -> {
            log.warn("Failed to fetch {}: {}", name, ex.toString());
            return null;
        });
    }
}