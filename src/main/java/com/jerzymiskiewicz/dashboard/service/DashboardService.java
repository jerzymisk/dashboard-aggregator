package com.jerzymiskiewicz.dashboard.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DashboardService {
    private final ExternalApiClient apiClient;
    private final RedisCache redisCache;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DASHBOARD_CACHE_KEY = "dashboard:lastSuccess";
    private static final long CACHE_TTL_SECONDS = 60;

    private static final Logger LOG = LoggerFactory.getLogger(DashboardService.class);

    public DashboardService(ExternalApiClient apiClient, RedisCache redisCache) {
        this.apiClient = apiClient;
        this.redisCache = redisCache;
    }
    public CompletableFuture<String> getDashboardJson() {
        LOG.debug("DashboardService.getDashboardJson invoked");
        CompletableFuture<JsonNode> weatherFuture = apiClient.getWeather();
        CompletableFuture<JsonNode> factFuture = apiClient.getRandomFact();
        CompletableFuture<JsonNode> ipFuture = apiClient.getPublicIp();
        CompletableFuture<Void> all = CompletableFuture.allOf(
                weatherFuture, factFuture, ipFuture
        );
        // 1) Пытаемся собрать свежий ответ и сохранить его в Redis
        CompletableFuture<String> freshJsonFuture = all.thenApply(v -> {
            JsonNode weather = weatherFuture.join();
            JsonNode fact = factFuture.join();
            JsonNode ip = ipFuture.join();
            return buildDashboardJson(weather, fact, ip);
        }).thenCompose(json -> saveToCacheWithTtl(DASHBOARD_CACHE_KEY, json, CACHE_TTL_SECONDS)
                .thenApply(v -> json)
        );
        // 2) Если всё хорошо — просто вернём freshFuture,
        //    если ошибка — пытаемся взять из кеша
        return freshJsonFuture.handle((json, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(json);
            } else {
                LOG.warn("Failed to build fresh dashboard JSON, falling back to cache", ex);
                // fallback на кэш
                return redisCache.get(DASHBOARD_CACHE_KEY)
                        .thenApply(opt -> opt.orElseThrow(
                                () -> new CompletionException(
                                        new RuntimeException("Failed to fetch APIs and cache is empty", ex)
                                )
                        ));
            }
        }).thenCompose(Function.identity());
    }

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

    private CompletableFuture<Void> saveToCacheWithTtl(String key, String json, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return redisCache.save(key, json);
        }
        return redisCache.save(key, json, ttlSeconds);
    }
}
