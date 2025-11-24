package com.jerzymiskiewicz.dashboard.service;

import io.lettuce.core.RedisConnectionException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisCacheTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;
    private static final String KEY = "test:key";
    private static final String VALUE = "hello-redis";

    @Test
    void givenRedisAvailable_whenSaveAndGet_thenReturnsSavedValue() {
        try (RedisCache cache = new RedisCache(HOST, PORT)) {

            // Проверяем: если Redis недоступен → тест пропускается (а не ломает билд)
            try {
                cache.save(KEY, VALUE).join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RedisConnectionException) {
                    assumeTrue(false, "Redis is not running — skipping RedisCacheTest");
                }
                throw e; // если ошибка другого типа → пусть падает
            }

            Optional<String> result = cache.get(KEY).join();

            assertTrue(result.isPresent(), "Ожидалось наличие значения в кэше");
            assertEquals(VALUE, result.get());
        }
    }
}