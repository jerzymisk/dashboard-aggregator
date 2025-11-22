package com.jerzymiskiewicz.dashboard.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class RedisCache implements AutoCloseable {
    private static final String REDIS_URI_PREFIX = "redis://";

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> redisConnection;
    private final RedisAsyncCommands<String, String> commands;

    public RedisCache(String host, int port) {
        Objects.requireNonNull(host, "host must not be null");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in range 1..65535");
        }
        this.redisClient = RedisClient.create(String.format("%s%s:%d", REDIS_URI_PREFIX, host, port));
        this.redisConnection = redisClient.connect();
        this.commands = redisConnection.async();
    }

    /**
     * Сохранить значение (без TTL).
     */
    public CompletableFuture<Void> save(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        return commands.set(key, value)
                .toCompletableFuture()
                .thenAccept(status -> {});
    }

    /**
     * Сохранить значение с TTL в секундах.
     * ttlSeconds == 0 — эквивалентно сохранению без TTL.
     */
    public CompletableFuture<Void> save(String key, String value, long ttlSeconds) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must be >= 0");
        }
        if (ttlSeconds == 0) {
            return commands.set(key, value)
                    .toCompletableFuture()
                    .thenAccept(status -> {});
        }
        return commands.setex(key, ttlSeconds, value)
                .toCompletableFuture()
                .thenAccept(status -> {});
    }

    /**
     * Получить значение из Redis (Optional.empty(), если ключа нет).
     */
    public CompletableFuture<Optional<String>> get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return commands.get(key)
                .toCompletableFuture()
                .thenApply(Optional::ofNullable);
    }

    @Override
    public void close() {
        redisConnection.close();
        redisClient.shutdown();
    }
}
