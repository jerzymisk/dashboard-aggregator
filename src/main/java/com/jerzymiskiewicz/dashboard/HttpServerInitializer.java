package com.jerzymiskiewicz.dashboard;

import com.jerzymiskiewicz.dashboard.service.DashboardService;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.Objects;

/**
 * Configures the HTTP pipeline for each new Netty channel.
 *
 * Pipeline order:
 *   1) HttpServerCodec      — decodes/encodes HTTP messages
 *   2) HttpObjectAggregator — aggregates chunks into FullHttpRequest
 *   3) HealthHandler        — handles /health endpoint
 *   4) DashboardHandler     — main business logic for /api/dashboard
 *
 * The HealthHandler is intentionally placed BEFORE DashboardHandler
 * to ensure ultra-fast response for health probes.
 */
public final class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    /** Business service used by the DashboardHandler. */
    private final DashboardService dashboardService;

    /** Max allowed size of aggregated HTTP request content. */
    private final int maxContentLength;

    /** Default buffer size = 1 MB. */
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1_048_576;

    /**
     * Creates initializer with default max content length.
     */
    public HttpServerInitializer(DashboardService dashboardService) {
        this(dashboardService, DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates initializer with a custom max aggregated content length.
     */
    public HttpServerInitializer(DashboardService dashboardService, int maxContentLength) {
        this.dashboardService = Objects.requireNonNull(dashboardService, "dashboardService must not be null");

        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be > 0");
        }
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        setupHttpPipeline(pipeline);
    }

    /**
     * Configures the Netty pipeline with HTTP codec, aggregator,
     * health endpoint handler and dashboard business handler.
     */
    private void setupHttpPipeline(ChannelPipeline pipeline) {

        // Decodes HTTP bytes -> HTTP objects and encodes responses.
        pipeline.addLast(new HttpServerCodec());

        // Converts chunked HTTP messages into FullHttpRequest.
        pipeline.addLast(new HttpObjectAggregator(maxContentLength));

        // Lightweight health check, should run before main routing.
        pipeline.addLast(new com.jerzymiskiewicz.dashboard.HealthHandler());

        // Main business handler for /api/dashboard
        pipeline.addLast(new DashboardHandler(dashboardService));
    }
}