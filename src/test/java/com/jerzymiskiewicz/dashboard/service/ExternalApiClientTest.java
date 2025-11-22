package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExternalApiClientTest {

    private HttpClient mockClient;
    private ObjectMapper objectMapper;
    private ExternalApiClient apiClient;

    @BeforeEach
    void setUp() {
        mockClient = mock(HttpClient.class);
        objectMapper = new ObjectMapper();
        apiClient = new ExternalApiClient(mockClient, objectMapper);
    }

    @Test
    void given2xx_whenGetRandomFact_thenReturnParsedJson() {
        mockClientResponds(200, "{\"value\": 42}");

        JsonNode result = apiClient.getRandomFact().join();

        assertEquals(42, result.get("value").asInt());
    }

    @Test
    void givenNon2xx_whenGetRandomFact_thenCompleteExceptionally() {
        mockClientResponds(500, "Server error");

        CompletableFuture<JsonNode> future = apiClient.getRandomFact();

        assertTrue(future.isCompletedExceptionally());
        assertThrows(CompletionException.class, future::join);
    }

    @SuppressWarnings("unchecked")
    private void mockClientResponds(int status, String body) {
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(status);
        when(mockResponse.body()).thenReturn(body);

        CompletableFuture<HttpResponse<String>> httpFuture = CompletableFuture.completedFuture(mockResponse);
        when(mockClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpFuture);
    }
}
