package com.jerzymiskiewicz.dashboard.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// ... existing code ...
class RedisCacheTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;
    private static final String KEY = "test:key";
    private static final String VALUE = "hello-redis";

    @Test
    void givenKeySaved_whenGet_thenReturnsValue() {
        try (RedisCache cache = new RedisCache(HOST, PORT)) {
            cache.save(KEY, VALUE).join();

            String actual = cache.get(KEY)
                    .join()
                    .orElseThrow(() -> new AssertionError("Ожидалось наличие значения в кэше"));

            assertEquals(VALUE, actual);
        }
    }
}
