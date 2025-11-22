package com.jerzymiskiewicz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ExternalApiClient {

    private static final String WEATHER_API_URL = "https://api.open-meteo.com/v1/forecast";
    private static final double DEFAULT_LATITUDE = 51.107883;
    private static final double DEFAULT_LONGITUDE = 17.038538;
    private static final String RANDOM_FACT_URL = "https://uselessfacts.jsph.pl/api/v2/facts/random";
    private static final String PUBLIC_IP_URL = "https://api.ipify.org/?format=json";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ExternalApiClient() {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build(), new ObjectMapper());
    }

    public ExternalApiClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<JsonNode> getWeather() {
        String url = WEATHER_API_URL +
                "?latitude=" + DEFAULT_LATITUDE +
                "&longitude=" + DEFAULT_LONGITUDE +
                "&current_weather=true";
        HttpRequest request = buildGetRequest(url);
        return sendAndParseJson(request);
    }

    public CompletableFuture<JsonNode> getRandomFact() {
        HttpRequest request = buildGetRequest(RANDOM_FACT_URL);
        return sendAndParseJson(request);
    }

    public CompletableFuture<JsonNode> getPublicIp() {
        HttpRequest request = buildGetRequest(PUBLIC_IP_URL);
        return sendAndParseJson(request);
    }

    private CompletableFuture<JsonNode> sendAndParseJson(HttpRequest request) {
        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    String body = response.body();
                    if (status >= 200 && status < 300) {
                        return toJsonNode(body);
                    }
                    throw new CompletionException(
                            new RuntimeException("HTTP error " + status + " for URL " + request.uri())
                    );
                });
    }

    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .GET()
                .build();
    }

    private JsonNode toJsonNode(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
}
