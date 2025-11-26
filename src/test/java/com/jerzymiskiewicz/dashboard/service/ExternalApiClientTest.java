package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExternalApiClient.
 * We mock HttpClient to avoid real HTTP calls and verify JSON parsing + error handling.
 */
class ExternalApiClientTest {

    private HttpClient mockClient;
    private ObjectMapper objectMapper;
    private ExternalApiClient apiClient;



    private static final String FACT_JSON = "{\"text\": \"hello\"}";
    private static final String INVALID_JSON = "not-a-json-body";

    @BeforeEach
    void setUp() {
        // Using mocked HttpClient and real ObjectMapper
        mockClient = mock(HttpClient.class);
        objectMapper = new ObjectMapper();
        apiClient = new ExternalApiClient(mockClient, objectMapper);
    }

    @Test
    void given2xxResponse_whenFetchRandomFact_thenReturnsParsedJson() {
        // given
        stubSendAsyncResponse(200, FACT_JSON);

        // when
        JsonNode result = apiClient.fetchRandomFact().join();

        // then
        assertEquals("hello", result.get("fact").asText());
    }

    @Test
    void givenNon2xxResponse_whenFetchRandomFact_thenCompletesExceptionally() {
        // given
        stubSendAsyncResponse(500, "Server error");

        // when
        CompletableFuture<JsonNode> future = apiClient.fetchRandomFact();

        // then
        assertCompletesWithCompletionException(future);
    }

    @Test
    void givenInvalidJsonBody_whenFetchRandomFact_thenCompletesExceptionally() {
        // given
        stubSendAsyncResponse(200, INVALID_JSON);

        // when
        CompletableFuture<JsonNode> future = apiClient.fetchRandomFact();

        // then
        CompletionException ex = assertCompletesWithCompletionException(future);
        assertNotNull(ex.getCause(), "Underlying JSON parsing exception should be present");
    }

    private void stubSendAsyncResponse(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(status);
        when(mockResponse.body()).thenReturn(body);

        CompletableFuture<HttpResponse<String>> httpFuture =
                CompletableFuture.completedFuture(mockResponse);

        when(mockClient.sendAsync(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpFuture);
    }

    private static CompletionException assertCompletesWithCompletionException(CompletableFuture<?> future) {
        assertTrue(future.isCompletedExceptionally());
        return assertThrows(CompletionException.class, future::join);
    }
}