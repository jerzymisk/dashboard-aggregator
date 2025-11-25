package com.jerzymiskiewicz.dashboard;

import com.jerzymiskiewicz.dashboard.service.DashboardService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP request handler that exposes:
 *
 *   GET /api/dashboard → aggregated JSON from external APIs
 *   GET /health → instant health probe ("status": "UP")
 *
 * This handler is non-blocking: all heavy work is done asynchronously.
 */
public final class DashboardHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardHandler.class);

    /** REST routing constants */
    private static final String DASHBOARD_PATH = "/api/dashboard";
    private static final String HEALTH_PATH = "/health";

    /** Pre-rendered static JSON for health endpoint */
    private static final String HEALTH_UP_JSON = "{\"status\":\"UP\"}";

    /** MIME type for JSON (UTF-8) */
    private static final String JSON_CT = "application/json; charset=utf-8";

    private final DashboardService dashboardService;

    public DashboardHandler(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {

        // --- Health check: synchronous, ultra-fast, no external calls ---
        if (isHealthGet(req)) {
            sendJson(ctx, HttpResponseStatus.OK, HEALTH_UP_JSON);
            return;
        }

        // --- Only GET /api/dashboard is handled; everything else → 404 ---
        if (!isDashboardGet(req)) {
            send404(ctx);
            return;
        }

        // --- Non-blocking async processing ---
        dashboardService.getDashboardJson()
                .whenComplete((json, err) -> {
                    if (err != null) {
                        LOGGER.error("Failed to fetch dashboard", err);
                        sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                buildErrorJson("Failed to fetch dashboard"));
                    } else {
                        sendJson(ctx, HttpResponseStatus.OK, json);
                    }
                });
    }

    /** True if request is GET /api/dashboard (query params ignored). */
    private boolean isDashboardGet(FullHttpRequest r) {
        return isGetTo(r, DASHBOARD_PATH);
    }

    /** True if request is GET /health (query params ignored). */
    private boolean isHealthGet(FullHttpRequest r) {
        return isGetTo(r, HEALTH_PATH);
    }

    /**
     * Utility method that checks whether the request is GET to a specific path.
     * Query parameters are ignored by decoding the normalized path.
     */
    private boolean isGetTo(FullHttpRequest r, String expectedPath) {
        return r.method().equals(HttpMethod.GET) && path(r).equals(expectedPath);
    }

    /**
     * Extracts clean path without query parameters.
     * Example: "/api/dashboard?x=1" → "/api/dashboard"
     */
    private String path(FullHttpRequest r) {
        return new QueryStringDecoder(r.uri()).path();
    }

    /**
     * Sends a JSON HTTP response with proper headers and closes connection.
     */
    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        ByteBuf content = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);

        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                content
        );

        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, JSON_CT);
        HttpUtil.setContentLength(resp, content.readableBytes());

        // Close connection after sending — no keep-alive
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Sends a 404 Not Found without body.
     */
    private void send404(ChannelHandlerContext ctx) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_FOUND,
                Unpooled.EMPTY_BUFFER
        );

        HttpUtil.setContentLength(resp, 0);
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Builds a minimal error JSON with safe escaping.
     */
    private String buildErrorJson(String msg) {
        String escaped = msg == null ? "" : msg.replace("\"", "\\\"");
        return "{\"error\":\"" + escaped + "\"}";
    }
}