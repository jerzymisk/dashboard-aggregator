package com.jerzymiskiewicz.dashboard;
import com.jerzymiskiewicz.dashboard.service.DashboardService;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1_048_576;

    private final DashboardService dashboardService;
    private final int maxContentLength;

    public HttpServerInitializer(DashboardService dashboardService) {
        this(dashboardService, DEFAULT_MAX_CONTENT_LENGTH);
    }

    public HttpServerInitializer(DashboardService dashboardService, int maxContentLength) {
        this.dashboardService = dashboardService;
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        final ChannelPipeline pipeline = channel.pipeline();
        configureHttpPipeline(pipeline);
    }

    private void configureHttpPipeline(ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxContentLength));
        pipeline.addLast(new DashboardHandler(dashboardService));
    }
}
