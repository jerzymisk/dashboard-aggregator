package com.jerzymiskiewicz.dashboard.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Simple async Redis cache wrapper built on top of Lettuce.
 *
 * Responsibilities:
 *  - Creating and managing Redis client/connection
 *  - Validating keys/values and ports
 *  - Providing convenient async get/save methods with optional TTL
 */
public class RedisCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisCache.class);

    /** Prefix for building redis:// URI. */
    private static final String REDIS_URI_PREFIX = "redis://";

    /** Valid TCP port range. */
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    /** Helper constant for rounding TTL up to 1 second. */
    private static final long ONE_SECOND = 1L;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> redisConnection;
    private final RedisAsyncCommands<String, String> commands;

    /**
     * Creates a Redis cache for given host and port.
     * This immediately opens a connection to Redis.
     */
    public RedisCache(String host, int port) {
        Objects.requireNonNull(host, "host must not be null");
        validatePort(port);

        String uri = buildRedisUri(host, port);
        LOG.info("Connecting to Redis at {}", uri);

        this.redisClient = RedisClient.create(uri);
        this.redisConnection = redisClient.connect();
        this.commands = redisConnection.async();
    }

    /**
     * Save a value without TTL.
     * Equivalent to Redis SET key value.
     */
    public CompletableFuture<Void> save(String key, String value) {
        validateKeyAndValue(key, value);
        LOG.debug("Redis SET key={} (no TTL)", key);
        return setWithOptionalTtl(key, value, null);
    }

    /**
     * Save a value with TTL in seconds.
     * ttlSeconds == 0 → same as save(key, value) without TTL.
     */
    public CompletableFuture<Void> save(String key, String value, long ttlSeconds) {
        validateKeyAndValue(key, value);

        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must be >= 0");
        }

        if (ttlSeconds == 0) {
            LOG.debug("Redis SET key={} (no TTL)", key);
            return setWithOptionalTtl(key, value, null);
        }

        LOG.debug("Redis SETEX key={} ttlSeconds={}", key, ttlSeconds);
        return setWithOptionalTtl(key, value, ttlSeconds);
    }

    /**
     * Save a value with TTL expressed as Duration.
     *
     * Rules:
     *  - Duration.ZERO → no TTL (plain SET)
     *  - Sub-second positive durations are rounded up to 1 second
     */
    public CompletableFuture<Void> save(String key, String value, Duration ttl) {
        validateKeyAndValue(key, value);
        Objects.requireNonNull(ttl, "ttl must not be null");

        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be >= PT0S");
        }

        if (ttl.isZero()) {
            LOG.debug("Redis SET key={} (no TTL)", key);
            return setWithOptionalTtl(key, value, null);
        }

        long seconds = normalizeTtlSeconds(ttl);
        LOG.debug("Redis SETEX key={} ttlSeconds={}", key, seconds);
        return setWithOptionalTtl(key, value, seconds);
    }

    /**
     * Fetch a value from Redis.
     *
     * @return a future with:
     *         - Optional.of(value) if key exists
     *         - Optional.empty() if key is missing
     */
    public CompletableFuture<Optional<String>> get(String key) {
        validateKey(key);
        LOG.debug("Redis GET key={}", key);

        return commands.get(key)
                .toCompletableFuture()
                .thenApply(Optional::ofNullable);
    }

    /**
     * Closes underlying Redis connection and client.
     * This method is idempotent and safe to call multiple times.
     */
    @Override
    public void close() {
        LOG.info("Closing Redis connection");
        try {
            if (redisConnection.isOpen()) {
                redisConnection.close();
            }
        } catch (Exception e) {
            LOG.warn("Error while closing Redis connection", e);
        }

        try {
            redisClient.shutdown();
        } catch (Exception e) {
            LOG.warn("Error while shutting down Redis client", e);
        }
    }

    // -------------------- Helpers --------------------

    /**
     * Validate that the port is in the valid TCP port range.
     */
    private static void validatePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be in range " + MIN_PORT + ".." + MAX_PORT);
        }
    }

    /**
     * Validate Redis key and value are not null and key is not blank.
     */
    private static void validateKeyAndValue(String key, String value) {
        validateKey(key);
        validateValue(value);
    }

    /**
     * Validates that key is not null and not blank.
     */
    private static void validateKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    /**
     * Validates that value is not null.
     */
    private static void validateValue(String value) {
        Objects.requireNonNull(value, "value must not be null");
    }

    /**
     * Build a redis:// URI from host and port.
     */
    private static String buildRedisUri(String host, int port) {
        return String.format("%s%s:%d", REDIS_URI_PREFIX, host, port);
    }

    /**
     * Normalize Duration to whole seconds for SETEX:
     *  - If duration is between 0 and 1 second (exclusive), returns 1.
     *  - Otherwise returns ttl.getSeconds().
     */
    private static long normalizeTtlSeconds(Duration ttl) {
        long seconds = ttl.getSeconds();
        return seconds == 0 ? ONE_SECOND : seconds;
    }

    /**
     * Common setter that applies optional TTL:
     *  - ttlSecondsNullable == null → plain SET
     *  - otherwise → SETEX with given TTL in seconds
     */
    private CompletableFuture<Void> setWithOptionalTtl(String key,
                                                       String value,
                                                       Long ttlSecondsNullable) {
        if (ttlSecondsNullable == null) {
            return commands.set(key, value)
                    .toCompletableFuture()
                    .thenAccept(status ->
                            LOG.debug("Redis SET status for {} -> {}", key, status));
        }

        return commands.setex(key, ttlSecondsNullable, value)
                .toCompletableFuture()
                .thenAccept(status ->
                        LOG.debug("Redis SETEX status for {} -> {}", key, status));
    }
}