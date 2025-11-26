# Dashboard Aggregator

A small Netty-based HTTP service that aggregates data from several external APIs, caches the result in Redis, and exposes it as a JSON dashboard.

## Features

- **Netty HTTP server** running on port `8080`
- **Two endpoints**:
    - `GET /api/dashboard` – returns aggregated JSON from external APIs (weather, random fact, public IP), with Redis caching
    - `GET /health` – lightweight health check returning `{"status":"UP"}`
- **Async non-blocking I/O** using:
    - Java `HttpClient` (for external APIs)
    - Lettuce Redis client (async commands)
- **Redis cache**:
    - Key: `dashboard:lastSuccess`
    - TTL: 60 seconds
    - Transparent cache HIT/MISS logic in `DashboardService`
- **Clean separation of concerns**:
    - `ExternalApi` – abstraction over external HTTP APIs
    - `ExternalApiClient` – production implementation
    - `RedisCache` – simple async cache wrapper
    - `DashboardService` – orchestration and aggregation logic
    - `DashboardHandler` / `HealthHandler` / `HttpServerInitializer` / `NettyServer` – HTTP layer

---

## Architecture Overview

### HTTP Layer

- **NettyServer**
    - Boots Netty on port `8080`
    - Reads Redis host/port from environment variables:
        - `REDIS_HOST` (default: `redis`)
        - `REDIS_PORT` (default: `6379`)
    - Creates `RedisCache`, `ExternalApiClient`, `DashboardService`
    - Configures pipeline via `HttpServerInitializer`

- **HttpServerInitializer**
    - Pipeline:
        - `HttpServerCodec`
        - `HttpObjectAggregator`
        - `HealthHandler`
        - `DashboardHandler`

- **HealthHandler**
    - Handles `GET /health`
    - Always returns:
      ```json
      {"status":"UP"}
      ```

- **DashboardHandler**
    - Handles `GET /api/dashboard`
    - Returns JSON with:
        - weather info
        - random fact
        - public IP
    - For unknown paths returns `404 NOT_FOUND`

### Service Layer

- **ExternalApi**
    - Contract for async external API calls:
        - `fetchWeather()`
        - `fetchRandomFact()`
        - `fetchPublicIp()`

- **ExternalApiClient**
    - Implements `ExternalApi`
    - Non-blocking HTTP calls using Java `HttpClient`
    - Validates JSON fields
    - Safe parsing with Jackson `ObjectMapper`

- **RedisCache**
    - Async wrapper over Lettuce Redis client
    - Provides:
        - `save`
        - `save with TTL`
        - `get`
    - Validates keys, throws on invalid TTL
    - `close()` is idempotent

- **DashboardService**
    - Algorithm:
        1. Try cache → return cached if exists
        2. On cache MISS:
            - Fetch weather + fact + IP in parallel
            - Build combined JSON
            - Save to Redis with TTL
            - Return JSON (even if Redis save fails)
    - Uses an internal record `DashboardData`

---

## Endpoints

### `GET /health`

Returns:

```json
{"status": "UP"}

GET /api/dashboard

Example:

{
  "weather": { "temp": 1, ... },
  "fact":    { "fact": "Some random fact" },
  "ip":      { "ip": "1.2.3.4" }
}


⸻

Requirements
	•	Java 21
	•	Maven 3.9+
	•	Redis (redis:6379)
	•	Docker (optional, for building/running via compose)

⸻

Running Tests

mvn clean test

Test suites:
	•	RedisCacheTest – integration-style tests with Redis
	•	ExternalApiClientTest – mocked HttpClient
	•	DashboardServiceTest – HIT/MISS logic
	•	DashboardHandlerTest – routing via EmbeddedChannel

Make sure Redis is running locally before running tests.


⸻

Running via Docker — ONE COMMAND

This project includes:
	•	Dockerfile — multi-stage build (Maven → Runtime)
	•	docker-compose.yml — Redis + App with healthcheck

Start everything with one command:

docker compose up --build

This will:
	•	Build the JAR inside Docker
	•	Build the runtime image
	•	Start Redis
	•	Wait until Redis is healthy
	•	Start your application
	•	Expose:
	•	http://localhost:8080/health
	•	http://localhost:8080/api/dashboard

Stop:

docker compose down


⸻

Running the Application Locally (without Docker)

Build:

mvn clean package

Run:

java -jar target/dashboard-aggregator-1.0-SNAPSHOT.jar

Override Redis host/port:

export REDIS_HOST=localhost
export REDIS_PORT=6379

Test manually:

curl http://localhost:8080/health
curl http://localhost:8080/api/dashboard

Jerzy Miskiewicz
