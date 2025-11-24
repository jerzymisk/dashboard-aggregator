package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    private static final String CACHE_KEY = "dashboard:lastSuccess";
    private static final long CACHE_TTL_SECONDS = 60L;

    @Test
    void givenCacheHit_whenGetDashboardJson_thenReturnsCachedValueAndSkipsApis() {
        ExternalApiClient apiClient = mock(ExternalApiClient.class);
        RedisCache redisCache = mock(RedisCache.class);
        DashboardService service = new DashboardService(apiClient, redisCache);

        String cachedJson = "{\"from\":\"cache\"}";
        when(redisCache.get(CACHE_KEY))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(cachedJson)));

        String result = service.getDashboardJson().join();

        assertEquals(cachedJson, result);
        // при cache hit внешние сервисы не должны вызываться
        verifyNoInteractions(apiClient);
    }

    @Test
    void givenCacheMiss_andApisSucceed_whenGetDashboardJson_thenAggregatesAndSavesToCache() {
        ExternalApiClient apiClient = mock(ExternalApiClient.class);
        RedisCache redisCache = mock(RedisCache.class);
        DashboardService service = new DashboardService(apiClient, redisCache);

        // 1) Кэш пустой
        when(redisCache.get(CACHE_KEY))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        // 2) API возвращают данные
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode weather = mapper.createObjectNode().put("temp", 20);
        ObjectNode fact = mapper.createObjectNode().put("text", "some fact");
        ObjectNode ip = mapper.createObjectNode().put("ip", "1.2.3.4");

        when(apiClient.getWeather()).thenReturn(CompletableFuture.completedFuture(weather));
        when(apiClient.getRandomFact()).thenReturn(CompletableFuture.completedFuture(fact));
        when(apiClient.getPublicIp()).thenReturn(CompletableFuture.completedFuture(ip));

        when(redisCache.save(eq(CACHE_KEY), anyString(), eq(CACHE_TTL_SECONDS)))
                .thenReturn(CompletableFuture.completedFuture(null));

        String json = service.getDashboardJson().join();

        assertTrue(json.contains("\"weather\""));
        assertTrue(json.contains("\"fact\""));
        assertTrue(json.contains("\"ip\""));

        verify(apiClient).getWeather();
        verify(apiClient).getRandomFact();
        verify(apiClient).getPublicIp();
        verify(redisCache).save(eq(CACHE_KEY), anyString(), eq(CACHE_TTL_SECONDS));
    }
}