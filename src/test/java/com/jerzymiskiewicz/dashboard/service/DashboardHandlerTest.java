package com.jerzymiskiewicz.dashboard.service;

import com.jerzymiskiewicz.dashboard.DashboardHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

// Tests HTTP handler behaviour (routing, status codes, JSON response)
class DashboardHandlerTest {

    /**
     * Simple stub DashboardService that always returns a predefined JSON future.
     * We still call the real super-constructor with dummy non-null dependencies
     * to satisfy "requireNonNull" checks, but we completely override the method
     * used in this test.
     */
    private static final class StubDashboardService extends DashboardService {

        private final CompletableFuture<String> response;

        StubDashboardService(String jsonBody) {
            super(
                    // Dummy ExternalApi, never used in this stub
                    new ExternalApi() {
                        @Override
                        public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> fetchWeather() {
                            return CompletableFuture.failedFuture(
                                    new UnsupportedOperationException("not used in StubDashboardService"));
                        }

                        @Override
                        public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> fetchRandomFact() {
                            return CompletableFuture.failedFuture(
                                    new UnsupportedOperationException("not used in StubDashboardService"));
                        }

                        @Override
                        public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> fetchPublicIp() {
                            return CompletableFuture.failedFuture(
                                    new UnsupportedOperationException("not used in StubDashboardService"));
                        }
                    },
                    // Dummy RedisCache, never used in this stub
                    new RedisCache("localhost", 6379),
                    new ObjectMapper()
            );
            this.response = CompletableFuture.completedFuture(jsonBody);
        }

        @Override
        public CompletableFuture<String> getDashboardJson() {
            // Always return predefined JSON without touching real APIs or Redis
            return response;
        }
    }

    // Introduced constants to avoid magic strings and improve readability
    private static final String PATH_DASHBOARD = "/api/dashboard";
    private static final String PATH_UNKNOWN = "/unknown";
    private static final String CONTENT_TYPE_JSON_UTF8 = "application/json; charset=utf-8";

    // Helper to build a channel with handler
    private static EmbeddedChannel newEmbeddedChannel(DashboardService service) {
        return new EmbeddedChannel(new DashboardHandler(service));
    }

    // Helper to create a GET request for given path
    private static FullHttpRequest getRequest(String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    }

    // Helper to write inbound request and read outbound response
    private static FullHttpResponse send(EmbeddedChannel channel, FullHttpRequest request) {
        channel.writeInbound(request);
        return channel.readOutbound();
    }

    @Test
    void whenGetDashboard_thenReturnsJsonAnd200() {
        String json = "{\"ok\":true}";
        StubDashboardService stubService = new StubDashboardService(json);

        EmbeddedChannel channel = newEmbeddedChannel(stubService);
        try {
            FullHttpResponse response = send(channel, getRequest(PATH_DASHBOARD));

            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals(CONTENT_TYPE_JSON_UTF8,
                    response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            assertEquals(json, response.content().toString(StandardCharsets.UTF_8));
        } finally {
            channel.close();
        }
    }

    @Test
    void whenUnknownPath_then404() {
        StubDashboardService stubService = new StubDashboardService("{\"ignored\":true}");

        EmbeddedChannel channel = newEmbeddedChannel(stubService);
        try {
            FullHttpResponse response = send(channel, getRequest(PATH_UNKNOWN));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        } finally {
            channel.close();
        }
    }
}