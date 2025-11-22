package com.jerzymiskiewicz.dashboard;

import com.jerzymiskiewicz.dashboard.service.DashboardService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpHeaderValues;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DashboardHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardHandler.class);
    private static final String DASHBOARD_PATH = "/api/dashboard";

    private final DashboardService dashboardService;

    public DashboardHandler(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!isDashboardGet(request)) {
            sendNotFound(ctx);
            return;
        }

        // ВАЖНО: не блокируем event loop, только регистрируем callback
        dashboardService.getDashboardJson()
                .whenComplete((json, throwable) -> {
                    if (throwable != null) {
                        LOG.error("Failed to fetch dashboard", throwable);
                        sendError(ctx, "Failed to fetch dashboard");
                    } else {
                        sendJson(ctx, HttpResponseStatus.OK, json);
                    }
                });
    }

    private boolean isDashboardGet(FullHttpRequest request) {
        return HttpMethod.GET.equals(request.method()) && DASHBOARD_PATH.equals(request.uri());
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        ByteBuf content = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                content
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendNotFound(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_FOUND
        );
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        String body = "{\"error\":\"" + escapeJson(message) + "\"}";
        sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, body);
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
