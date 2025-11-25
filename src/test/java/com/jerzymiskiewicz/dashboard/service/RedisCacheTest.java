package com.jerzymiskiewicz.dashboard.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RedisCacheTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;

    // Extracted constants to remove magic literals
    private static final String KEY_SIMPLE = "k1";
    private static final String VALUE_SIMPLE = "v1";
    private static final String KEY_MISSING = "missing:key";
    private static final String KEY_TTL_SECONDS = "ttl:key";
    private static final String KEY_TTL_DURATION = "ttl:duration";

    private static final Duration TTL_SUBSECOND = Duration.ofMillis(200);
    private static final Duration SLEEP_AFTER_1S_TTL = Duration.ofMillis(1500);
    private static final Duration SLEEP_AFTER_SUBSECOND_TTL = Duration.ofMillis(1200);

    // Helper to create a cache instance in a consistent way
    private static RedisCache newCache() {
        return new RedisCache(HOST, PORT);
    }

    // Helper to avoid copy-pasting try/catch and preserve interrupt flag
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Test interrupted while sleeping: " + e.getMessage());
        }
    }

    @Test
    void givenKeySaved_whenGet_thenReturnsValue() {
        try (RedisCache cache = newCache()) {
            cache.save(KEY_SIMPLE, VALUE_SIMPLE).join();

            Optional<String> result = cache.get(KEY_SIMPLE).join();

            assertTrue(result.isPresent());
            assertEquals(VALUE_SIMPLE, result.get());
        }
    }

    @Test
    void givenKeyNotExists_whenGet_thenReturnsEmpty() {
        try (RedisCache cache = newCache()) {
            Optional<String> result = cache.get(KEY_MISSING).join();
            assertTrue(result.isEmpty());
        }
    }

    @Test
    void whenSaveWithTtlSeconds_thenKeyExpires() {
        try (RedisCache cache = newCache()) {
            cache.save(KEY_TTL_SECONDS, "value", 1).join(); // TTL = 1 sec

            Optional<String> before = cache.get(KEY_TTL_SECONDS).join();
            assertTrue(before.isPresent());

            sleep(SLEEP_AFTER_1S_TTL);

            Optional<String> after = cache.get(KEY_TTL_SECONDS).join();
            assertTrue(after.isEmpty());
        }
    }

    @Test
    void whenSaveWithDurationSubSecond_thenTtlRoundsUpToOneSecond() {
        try (RedisCache cache = newCache()) {
            cache.save(KEY_TTL_DURATION, "value", TTL_SUBSECOND).join();

            Optional<String> before = cache.get(KEY_TTL_DURATION).join();
            assertTrue(before.isPresent());

            sleep(SLEEP_AFTER_SUBSECOND_TTL);

            Optional<String> after = cache.get(KEY_TTL_DURATION).join();
            assertTrue(after.isEmpty(), "TTL <1s should round up to 1s");
        }
    }

    @Test
    void whenKeyIsBlank_thenExceptionThrown() {
        try (RedisCache cache = newCache()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.save("  ", "value"));
        }
    }

    @Test
    void whenTtlNegative_thenExceptionThrown() {
        try (RedisCache cache = newCache()) {
            assertThrows(IllegalArgumentException.class,
                    () -> cache.save("key", "value", -5));
        }
    }

    @SuppressWarnings("resource")
    @Test
    void closeShouldBeIdempotent() {
        RedisCache cache = new RedisCache(HOST, PORT);
        assertDoesNotThrow(cache::close);
        assertDoesNotThrow(cache::close);
    }
}