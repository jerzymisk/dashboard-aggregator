package com.jerzymiskiewicz.dashboard;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;

/**
 * Netty handler for health-check endpoint.
 *
 * Responsibilities:
 *  - Intercept GET /health requests
 *  - Respond with static JSON {"status":"UP"}
 *  - Forward all other requests to the next handler
 */
public final class HealthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /** REST path for health-check. */
    private static final String HEALTH_PATH = "/health";

    /** Static JSON response for successful health check. */
    private static final String HEALTH_UP_JSON = "{\"status\":\"UP\"}";

    /** Content-Type header for JSON responses. */
    private static final String JSON_CT = "application/json; charset=utf-8";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {

        // If this is NOT GET /health → pass to next handler in the pipeline
        if (!isGetTo(request, HEALTH_PATH)) {
            // Important: retain() because Netty might release the buffer
            ctx.fireChannelRead(request.retain());
            return;
        }

        // Prepare JSON payload
        ByteBuf content = Unpooled.copiedBuffer(HEALTH_UP_JSON, StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                content
        );

        // Set headers
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, JSON_CT);
        HttpUtil.setContentLength(response, content.readableBytes());

        // Send response and close connection
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Returns true if request is a GET to the expected path.
     * Query parameters are ignored.
     */
    private static boolean isGetTo(FullHttpRequest request, String expectedPath) {
        return HttpMethod.GET.equals(request.method())
                && new QueryStringDecoder(request.uri()).path().equals(expectedPath);
    }
}