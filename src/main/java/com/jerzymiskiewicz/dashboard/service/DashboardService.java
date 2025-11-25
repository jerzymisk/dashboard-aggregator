package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Coordinates fetching dashboard data from:
 *  - external APIs (weather, fact, IP)
 *  - Redis cache (to avoid unnecessary calls)
 *
 * High-level algorithm:
 * <ol>
 *     <li>Try reading last successful dashboard JSON from Redis.</li>
 *     <li>If present → return cached JSON.</li>
 *     <li>If absent → fetch all API data in parallel.</li>
 *     <li>Build aggregated JSON, store in Redis with TTL, return it.</li>
 * </ol>
 */
public class DashboardService {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardService.class);

    // Redis cache config
    private static final String CACHE_KEY = "dashboard:lastSuccess";
    private static final long CACHE_TTL_SECONDS = 60;

    // JSON fields
    private static final String WEATHER_FIELD = "weather";
    private static final String FACT_FIELD = "fact";
    private static final String IP_FIELD = "ip";

    private final ExternalApi api;      // External API abstraction
    private final RedisCache redis;     // Redis async wrapper
    private final ObjectMapper mapper;  // JSON builder

    /**
     * Convenience constructor – creates default ObjectMapper.
     */
    public DashboardService(ExternalApi api, RedisCache redis) {
        this(api, redis, new ObjectMapper());
    }

    /**
     * Main constructor – makes testing easier.
     */
    public DashboardService(ExternalApi api, RedisCache redis, ObjectMapper mapper) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Returns dashboard JSON, preferring Redis cache.
     */
    public CompletableFuture<String> getDashboardJson() {
        LOG.info("getDashboardJson() invoked");
        return redis.get(CACHE_KEY)
                .thenCompose(this::resolveFromCacheOrFetch);
    }

    /**
     * If cache HIT → return cached JSON.
     * If cache MISS → fetch from external APIs.
     */
    private CompletableFuture<String> resolveFromCacheOrFetch(Optional<String> cachedOpt) {
        if (cachedOpt.isPresent()) {
            LOG.info("Cache HIT for key={}", CACHE_KEY);
            return CompletableFuture.completedFuture(cachedOpt.get());
        }

        LOG.info("Cache MISS for key={}, fetching external data…", CACHE_KEY);
        return fetchAndCache();
    }

    /**
     * Fetch weather, fact, and IP concurrently,
     * convert them to a single JSON object,
     * save to Redis (with TTL),
     * return final JSON.
     */
    private CompletableFuture<String> fetchAndCache() {
        return fetchDashboardData()
                .thenApply(this::toJson)
                .thenCompose(this::saveToCacheWithLogging);
    }

    /**
     * Fetch all 3 external API responses in parallel.
     * Uses the new fetch* methods.
     */
    private CompletableFuture<DashboardData> fetchDashboardData() {
        CompletableFuture<JsonNode> weatherF = api.fetchWeather();
        CompletableFuture<JsonNode> factF = api.fetchRandomFact();
        CompletableFuture<JsonNode> ipF = api.fetchPublicIp();

        // Combine 3 futures without blocking join()
        return weatherF
                .thenCombine(factF, (w, f) -> new DashboardData(w, f, null))
                .thenCombine(ipF, (df, ip) -> new DashboardData(df.weather(), df.fact(), ip));
    }

    /**
     * Saves JSON into Redis and logs result.
     * Redis failures DO NOT fail main future.
     */
    private CompletableFuture<String> saveToCacheWithLogging(String json) {
        return redis.save(CACHE_KEY, json, CACHE_TTL_SECONDS)
                .handle((ignored, ex) -> {
                    if (ex != null) {
                        LOG.warn("Failed to save JSON to Redis key={}", CACHE_KEY, ex);
                    } else {
                        LOG.info("Saved JSON to Redis key={} ttlSeconds={}",
                                CACHE_KEY, CACHE_TTL_SECONDS);
                    }
                    return json;
                });
    }

    /**
     * Build the JSON body:
     * {
     *   "weather": {...},
     *   "fact": {...},
     *   "ip": {...}
     * }
     */
    private ObjectNode buildRootNode(DashboardData data) {
        ObjectNode root = mapper.createObjectNode();
        root.set(WEATHER_FIELD, data.weather());
        root.set(FACT_FIELD, data.fact());
        root.set(IP_FIELD, data.ip());
        return root;
    }

    /**
     * Serialize an ObjectNode into JSON string.
     */
    private String toJson(ObjectNode root) {
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Serialize DashboardData → JSON string.
     */
    private String toJson(DashboardData data) {
        return toJson(buildRootNode(data));
    }

    /**
     * Typed container for aggregated data.
     */
    private record DashboardData(JsonNode weather, JsonNode fact, JsonNode ip) {}
}