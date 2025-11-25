package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for external API calls used by DashboardService.
 *
 * <p>This interface exposes asynchronous operations that fetch JSON payloads
 * from external services. All methods are explicitly asynchronous and return
 * {@link CompletableFuture}, making the contract clear and non-blocking.
 */
public interface ExternalApi {

    /**
     * Asynchronously fetches current weather data as JSON.
     *
     * @return future completed with JSON payload (e.g. containing "temp" field),
     *         or completed exceptionally on failure.
     */
    CompletableFuture<JsonNode> fetchWeather();

    /**
     * Asynchronously fetches a random fact as JSON.
     *
     * @return future completed with JSON payload (e.g. containing "value" field),
     *         or completed exceptionally on failure.
     */
    CompletableFuture<JsonNode> fetchRandomFact();

    /**
     * Asynchronously fetches public IP information as JSON.
     *
     * @return future completed with JSON payload (e.g. containing "ip" field),
     *         or completed exceptionally on failure.
     */
    CompletableFuture<JsonNode> fetchPublicIp();
}