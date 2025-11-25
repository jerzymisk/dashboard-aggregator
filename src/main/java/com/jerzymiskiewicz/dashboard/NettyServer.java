package com.jerzymiskiewicz.dashboard;

import com.jerzymiskiewicz.dashboard.service.DashboardService;
import com.jerzymiskiewicz.dashboard.service.ExternalApiClient;
import com.jerzymiskiewicz.dashboard.service.RedisCache;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application bootstrap class that starts a Netty HTTP server.
 *
 * Exposes two endpoints:
 *   - GET /api/dashboard : aggregated external data (weather + fact + IP)
 *   - GET /health        : lightweight server check
 *
 * This class is final and non-instantiable because it only bootstraps the application.
 */
public final class NettyServer {

    private static final Logger LOG = LoggerFactory.getLogger(NettyServer.class);

    /** Port where the HTTP server listens. */
    private static final int SERVER_PORT = 8080;

    /** Env variable names for Redis configuration. */
    private static final String ENV_REDIS_HOST = "REDIS_HOST";
    private static final String ENV_REDIS_PORT = "REDIS_PORT";

    /** Default Redis configurations (used when ENV vars are missing). */
    private static final String DEFAULT_REDIS_HOST = "localhost";
    private static final int DEFAULT_REDIS_PORT = 6379;

    /** Private constructor — this class is not meant to be instantiated. */
    private NettyServer() {}

    public static void main(String[] args) throws InterruptedException {

        // Boss group = accepts TCP connections
        // Worker group = handles I/O for each connection
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        // Resolve Redis connection settings
        RedisSettings redis = resolveRedisSettings();

        // RedisCache implements AutoCloseable → safe shutdown via try-with-resources
        try (RedisCache redisCache = new RedisCache(redis.host(), redis.port())) {

            ExternalApiClient apiClient = new ExternalApiClient();
            DashboardService dashboardService = new DashboardService(apiClient, redisCache);

            // Configure the Netty server
            ServerBootstrap bootstrap = createBootstrap(dashboardService, bossGroup, workerGroup);

            // Start server (bind to port)
            Channel serverChannel = bootstrap.bind(SERVER_PORT).sync().channel();

            LOG.info("🚀 Server started on: http://localhost:{}/api/dashboard", SERVER_PORT);
            LOG.info("❤  Health check:     http://localhost:{}/health", SERVER_PORT);
            LOG.info("🔌 Using Redis at:   {}:{}", redis.host(), redis.port());

            // Wait until server shuts down (block main thread)
            serverChannel.closeFuture().sync();

        } finally {
            // Graceful shutdown of Netty threads
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * Creates a fully configured Netty ServerBootstrap instance.
     */
    private static ServerBootstrap createBootstrap(DashboardService service,
                                                   EventLoopGroup bossGroup,
                                                   EventLoopGroup workerGroup) {

        return new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // Pipeline defined in HttpServerInitializer
                .childHandler(new HttpServerInitializer(service))
                // TCP: how many incoming connections can wait in queue
                .option(ChannelOption.SO_BACKLOG, 128)
                // Enables persistent HTTP connections
                .childOption(ChannelOption.SO_KEEPALIVE, true);
    }

    /**
     * Reads Redis host & port from environment variables.
     * Falls back to safe defaults when values are missing or invalid.
     */
    private static RedisSettings resolveRedisSettings() {
        String host = System.getenv().getOrDefault(ENV_REDIS_HOST, DEFAULT_REDIS_HOST);

        int port;
        try {
            port = Integer.parseInt(System.getenv().getOrDefault(ENV_REDIS_PORT, String.valueOf(DEFAULT_REDIS_PORT)));
        } catch (NumberFormatException ex) {
            LOG.warn("Invalid REDIS_PORT value, falling back to default {}", DEFAULT_REDIS_PORT);
            port = DEFAULT_REDIS_PORT;
        }

        return new RedisSettings(host, port);
    }

    /**
     * Small immutable record to hold Redis settings.
     */
    private record RedisSettings(String host, int port) {}
}