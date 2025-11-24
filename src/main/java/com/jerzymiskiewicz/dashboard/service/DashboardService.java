package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DashboardService {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardService.class);

    private static final String DASHBOARD_CACHE_KEY = "dashboard:lastSuccess";
    private static final long CACHE_TTL_SECONDS = 60L;

    private final ExternalApiClient apiClient;
    private final RedisCache redisCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DashboardService(ExternalApiClient apiClient, RedisCache redisCache) {
        this.apiClient = apiClient;
        this.redisCache = redisCache;
    }

    /**
     * Public API:
     * 1) Try to read from Redis cache.
     * 2) If cache hit – immediately return cached JSON.
     * 3) If cache misses – fetch fresh data from external APIs and store in Redis.
     */
    public CompletableFuture<String> getDashboardJson() {
        LOG.debug("DashboardService.getDashboardJson invoked");

        return redisCache.get(DASHBOARD_CACHE_KEY)
                .thenCompose(this::resolveFromCacheOrFetchFresh);
    }

    /**
     * Decide whether to use cached JSON or fetch fresh data.
     */
    private CompletableFuture<String> resolveFromCacheOrFetchFresh(Optional<String> cachedJson) {
        if (cachedJson.isPresent()) {
            LOG.debug("Dashboard cache HIT");
            return CompletableFuture.completedFuture(cachedJson.get());
        }

        LOG.debug("Dashboard cache MISS, fetching from external APIs");
        return fetchFreshAndCache();
    }

    /**
     * Fetch data from all external APIs, build JSON and store it in Redis.
     */
    private CompletableFuture<String> fetchFreshAndCache() {
        CompletableFuture<JsonNode> weatherFuture = apiClient.getWeather();
        CompletableFuture<JsonNode> factFuture = apiClient.getRandomFact();
        CompletableFuture<JsonNode> ipFuture = apiClient.getPublicIp();

        CompletableFuture<Void> all = CompletableFuture.allOf(
                weatherFuture, factFuture, ipFuture
        );

        return all
                .thenApply(ignored ->
                        buildDashboardJson(
                                weatherFuture.join(),
                                factFuture.join(),
                                ipFuture.join()
                        )
                )
                .thenCompose(json ->
                        saveToCacheWithTtl(DASHBOARD_CACHE_KEY, json, CACHE_TTL_SECONDS)
                                .thenApply(v -> json)
                )
                .exceptionally(ex -> {
                    LOG.error("Failed to fetch fresh dashboard data", ex);
                    // здесь уже нет смысла второй раз идти в кэш:
                    // если кэш был, мы бы его использовали раньше
                    throw new CompletionException(ex);
                });
    }

    /**
     * Build final dashboard JSON from parts.
     */
    private String buildDashboardJson(JsonNode weather, JsonNode fact, JsonNode ip) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("weather", weather);
        root.set("fact", fact);
        root.set("ip", ip);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Save JSON to Redis with optional TTL.
     */
    private CompletableFuture<Void> saveToCacheWithTtl(String key, String json, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return redisCache.save(key, json);
        }
        return redisCache.save(key, json, ttlSeconds);
    }
}