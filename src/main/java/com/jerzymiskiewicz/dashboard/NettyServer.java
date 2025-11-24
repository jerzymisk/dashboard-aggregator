package com.jerzymiskiewicz.dashboard;
import com.jerzymiskiewicz.dashboard.service.DashboardService;
import com.jerzymiskiewicz.dashboard.service.ExternalApiClient;
import com.jerzymiskiewicz.dashboard.service.RedisCache;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class NettyServer {

    private static final int HTTP_PORT = 8080;
    private static final int BOSS_THREADS = 1;
    private static final int SO_BACKLOG = 128;
    private static final String REDIS_HOST =
            System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int REDIS_PORT =
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    public static void main(String[] args) {
        final EventLoopGroup acceptorGroup = new NioEventLoopGroup(BOSS_THREADS);
        final EventLoopGroup ioWorkerGroup = new NioEventLoopGroup();
        final ServerConfiguration config = initializeServices(acceptorGroup, ioWorkerGroup);

        try {
            final ServerBootstrap bootstrap = configureBootstrap(config.getDashboardService(),
                    acceptorGroup, ioWorkerGroup);
            final ChannelFuture bindFuture = bootstrap.bind(HTTP_PORT).sync();
            final Channel serverChannel = bindFuture.channel();
            System.out.println("Server started on http://localhost:" + HTTP_PORT + "/api/dashboard");
            serverChannel.closeFuture().sync();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            System.err.println("Server interrupted, shutting down...");
        } finally {
            acceptorGroup.shutdownGracefully();
            ioWorkerGroup.shutdownGracefully();
            config.getRedisCache().close();
        }
    }

    private static ServerConfiguration initializeServices(EventLoopGroup acceptorGroup,
                                                          EventLoopGroup ioWorkerGroup) {
        final ExternalApiClient apiClient = new ExternalApiClient();
        final RedisCache redisCache = new RedisCache(REDIS_HOST, REDIS_PORT);
        final DashboardService dashboardService = new DashboardService(apiClient, redisCache);
        return new ServerConfiguration(dashboardService, redisCache);
    }

    private static ServerBootstrap configureBootstrap(DashboardService dashboardService,
                                                      EventLoopGroup acceptorGroup,
                                                      EventLoopGroup ioWorkerGroup) {
        return new ServerBootstrap()
                .group(acceptorGroup, ioWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new HttpServerInitializer(dashboardService))
                .option(ChannelOption.SO_BACKLOG, SO_BACKLOG)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
    }

    private static class ServerConfiguration {
        private final DashboardService dashboardService;
        private final RedisCache redisCache;

        ServerConfiguration(DashboardService dashboardService, RedisCache redisCache) {
            this.dashboardService = dashboardService;
            this.redisCache = redisCache;
        }

        DashboardService getDashboardService() {
            return dashboardService;
        }

        RedisCache getRedisCache() {
            return redisCache;
        }
    }
}
