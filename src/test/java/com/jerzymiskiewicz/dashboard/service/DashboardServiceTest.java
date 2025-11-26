package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // Extracted constants to avoid magic strings
    private static final String CACHE_KEY = "dashboard:lastSuccess";
    private static final String WEATHER_FIELD = "weather";
    private static final String FACT_FIELD = "fact";
    private static final String IP_FIELD = "ip";
    private static final String FACT_ERROR_FIELD = "fact_error";
    private static final String CACHED_JSON_SAMPLE = "{\"cached\":true}";

    @Test
    void whenCacheHit_thenExternalApisNotCalled() throws Exception {
        // given
        ExternalApi api = mock(ExternalApi.class);
        RedisCache redis = mock(RedisCache.class);
        stubCacheHit(redis, CACHED_JSON_SAMPLE);

        DashboardService service = newService(api, redis);

        // when
        String result = service.getDashboardJson().join();

        // then
        assertEquals(CACHED_JSON_SAMPLE, result);
        // External APIs should never be called on cache HIT
        verifyNoInteractions(api);
    }

    @Test
    void whenCacheMiss_thenApisCalledAndResultSavedToRedis() throws Exception {
        // given
        ExternalApi api = mock(ExternalApi.class);
        RedisCache redis = mock(RedisCache.class);

        stubCacheMiss(redis);

        JsonNode weather = mapper.readTree("{\"temp\": 1}");
        JsonNode fact = mapper.readTree("{\"text\": \"fun fact\"}");
        JsonNode ip = mapper.readTree("{\"ip\": \"1.2.3.4\"}");

        stubApiResponses(api, weather, fact, ip);

        when(redis.save(eq(CACHE_KEY), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));

        DashboardService service = newService(api, redis);

        // when
        String json = service.getDashboardJson().join();
        JsonNode root = mapper.readTree(json);

        // then: JSON structure is correct
        assertEquals(weather, root.get(WEATHER_FIELD));
        assertEquals(fact, root.get(FACT_FIELD));
        assertEquals(ip, root.get(IP_FIELD));

        // Redis must store the newly built value
        verify(redis).save(eq(CACHE_KEY), anyString(), anyLong());

        // External APIs must be called once
        verify(api).fetchWeather();
        verify(api).fetchRandomFact();
        verify(api).fetchPublicIp();
    }

    @Test
    void whenCacheMiss_andFactApiFails_thenDashboardStillReturnedWithoutCaching() throws Exception {
        // given
        ExternalApi api = mock(ExternalApi.class);
        RedisCache redis = mock(RedisCache.class);

        stubCacheMiss(redis);

        JsonNode weather = mapper.readTree("{\"temp\": 1}");
        JsonNode ip = mapper.readTree("{\"ip\": \"1.2.3.4\"}");

        // weather & ip succeed
        when(api.fetchWeather()).thenReturn(CompletableFuture.completedFuture(weather));
        when(api.fetchPublicIp()).thenReturn(CompletableFuture.completedFuture(ip));
        // fact API fails
        when(api.fetchRandomFact()).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("fact down"))
        );

        DashboardService service = newService(api, redis);

        // when
        String json = service.getDashboardJson().join();
        JsonNode root = mapper.readTree(json);

        // then: weather and ip are present
        assertEquals(weather, root.get(WEATHER_FIELD));
        assertEquals(ip, root.get(IP_FIELD));

        // fact is missing, but error field is present
        assertFalse(root.has(FACT_FIELD));
        assertEquals("unavailable", root.get(FACT_ERROR_FIELD).asText());

        // Redis must NOT be updated because not all APIs succeeded
        verify(redis, never()).save(eq(CACHE_KEY), anyString(), anyLong());
    }

    // --- Helpers to configure Redis mock ---

    private static void stubCacheHit(RedisCache redis, String cachedJson) {
        when(redis.get(CACHE_KEY))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(cachedJson)));
    }

    private static void stubCacheMiss(RedisCache redis) {
        when(redis.get(CACHE_KEY))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    }

    // --- Helpers to configure ExternalApi mock ---

    private static void stubApiResponses(ExternalApi api, JsonNode weather, JsonNode fact, JsonNode ip) {
        when(api.fetchWeather()).thenReturn(CompletableFuture.completedFuture(weather));
        when(api.fetchRandomFact()).thenReturn(CompletableFuture.completedFuture(fact));
        when(api.fetchPublicIp()).thenReturn(CompletableFuture.completedFuture(ip));
    }

    // Factory method for SUT
    private static DashboardService newService(ExternalApi api, RedisCache redis) {
        return new DashboardService(api, redis);
    }
}