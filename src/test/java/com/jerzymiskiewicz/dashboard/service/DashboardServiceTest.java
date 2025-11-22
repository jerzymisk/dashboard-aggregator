package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    private static final String CACHE_KEY = "dashboard:lastSuccess";
    private static final long CACHE_TTL_SECONDS = 60L;

    private ExternalApiClient apiClient;
    private RedisCache redisCache;
    private DashboardService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        apiClient = mock(ExternalApiClient.class);
        redisCache = mock(RedisCache.class);
        service = new DashboardService(apiClient, redisCache);
    }

    @Test
    void getDashboardJson_shouldAggregateAndSaveToCache_whenApisSucceed() {
        ObjectNode weather = mapper.createObjectNode().put("temp", 20);
        ObjectNode fact = mapper.createObjectNode().put("text", "some fact");
        ObjectNode ip = mapper.createObjectNode().put("ip", "1.2.3.4");

        when(apiClient.getWeather()).thenReturn(CompletableFuture.completedFuture(weather));
        when(apiClient.getRandomFact()).thenReturn(CompletableFuture.completedFuture(fact));
        when(apiClient.getPublicIp()).thenReturn(CompletableFuture.completedFuture(ip));

        when(redisCache.save(eq(CACHE_KEY), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        String json = service.getDashboardJson().join();

        assertTrue(json.contains("\"weather\""));
        assertTrue(json.contains("\"fact\""));
        assertTrue(json.contains("\"ip\""));

        verify(redisCache).save(eq(CACHE_KEY), anyString(), eq(CACHE_TTL_SECONDS));
    }

    @Test
    void getDashboardJson_shouldReturnCachedJson_whenAnyApiFails() {
        ObjectNode weather = mapper.createObjectNode().put("temp", 20);
        ObjectNode ip = mapper.createObjectNode().put("ip", "1.2.3.4");

        when(apiClient.getWeather()).thenReturn(CompletableFuture.completedFuture(weather));
        when(apiClient.getPublicIp()).thenReturn(CompletableFuture.completedFuture(ip));
        when(apiClient.getRandomFact())
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("API down")));

        String cachedJson = "{\"from\":\"cache\"}";
        when(redisCache.get(CACHE_KEY))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(cachedJson)));

        String result = service.getDashboardJson().join();

        assertEquals(cachedJson, result);
        verify(redisCache).get(CACHE_KEY);
    }
}
