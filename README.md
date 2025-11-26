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
    - Reads Redis host/port from environment:
        - `REDIS_HOST` (default: `redis`)
        - `REDIS_PORT` (default: `6379`)
    - Creates `RedisCache`, `ExternalApiClient`, `DashboardService`
    - Configures pipeline via `HttpServerInitializer`

- **HttpServerInitializer**
    - Pipeline:
        - `HttpServerCodec`
        - `HttpObjectAggregator`
        - `HealthHandler` (handles `GET /health`, otherwise forwards)
        - `DashboardHandler` (handles `GET /api/dashboard`, otherwise returns `404`)

- **HealthHandler**
    - For `GET /health` returns:
      ```json
      {"status":"UP"}
      ```
    - For any other request passes it further down the pipeline

- **DashboardHandler**
    - Routes:
        - `GET /api/dashboard` → calls `DashboardService#getDashboardJson()`
        - Any other path → `404 NOT_FOUND`
    - Sends JSON responses with `Content-Type: application/json; charset=utf-8`

### Service Layer

- **ExternalApi (interface)**
    - Asynchronous contract for external API calls:
        - `CompletableFuture<JsonNode> fetchWeather()`
        - `CompletableFuture<JsonNode> fetchRandomFact()`
        - `CompletableFuture<JsonNode> fetchPublicIp()`

- **ExternalApiClient (record)**
    - Implements `ExternalApi` using Java `HttpClient`
    - Non-blocking HTTP calls
    - Safe JSON parsing with `ObjectMapper`
    - Validates required JSON fields:
        - weather: field `"temp"`
        - fact: field `"value"`
        - IP: field `"ip"`

- **RedisCache**
    - Async wrapper around Lettuce Redis client
    - Methods:
        - `save(key, value)`
        - `save(key, value, long ttlSeconds)`
        - `save(key, value, Duration ttl)`
        - `get(key): CompletableFuture<Optional<String>>`
    - Validates keys and TTL, logs operations
    - `close()` is idempotent and safely closes client & connection

- **DashboardService**
    - High-level algorithm:
        1. Try to read cached JSON from Redis (`dashboard:lastSuccess`)
        2. On cache HIT → return cached JSON
        3. On cache MISS:
            - Fetch weather, fact, and IP in parallel using `ExternalApi`
            - Build combined JSON:
              ```json
              {
                "weather": { ... },
                "fact":    { ... },
                "ip":      { ... }
              }
              ```
            - Save to Redis with TTL
            - Return JSON to caller (even if Redis save failed)
    - Uses an internal `DashboardData` record to hold aggregated data

---

## Endpoints

### `GET /health`

- **Description:** Health check
- **Response:**
    - Status: `200 OK`
    - Body:
      ```json
      {"status":"UP"}
      ```

### `GET /api/dashboard`

- **Description:** Returns aggregated dashboard JSON
- **Response example:**
  ```json
  {
    "weather": {
      "temp": 1,
      "...": "..."
    },
    "fact": {
      "text": "Some random fact",
      "...": "..."
    },
    "ip": {
      "ip": "1.2.3.4"
    }
  }


⸻

Requirements
•	Java: 21 (LTS)
•	Maven: 3.9+
•	Redis: available on REDIS_HOST:REDIS_PORT
•	(Optional) Docker: to run Redis / build containers easily

⸻

Running Redis with Docker

You can start a local Redis instance using Docker:

docker run --rm -p 6379:6379 --name redis \
redis:7-alpine

This exposes Redis on localhost:6379, which matches the default test configuration.

⸻

Running Tests

All tests are standard JUnit 5 tests executed via Maven.

# From the project root
mvn clean test

What is covered:
•	RedisCacheTest – integration-like tests against a real Redis (on localhost:6379)
•	ExternalApiClientTest – mocks HttpClient, verifies JSON parsing & error handling
•	DashboardServiceTest – verifies cache HIT/MISS logic and JSON structure
•	DashboardHandlerTest – verifies HTTP routing & status codes with EmbeddedChannel

Make sure Redis is running before executing the tests.

⸻

Running the Application Locally

From the project root:

# Build the project
mvn clean package

Then run the Netty server (assuming the default JAR name):

java -cp target/dashboard-aggregator-1.0-SNAPSHOT.jar \
com.jerzymiskiewicz.dashboard.NettyServer

Or if you use a fat JAR with a manifest (depending on your Maven configuration):

java -jar target/dashboard-aggregator-1.0-SNAPSHOT.jar

Environment variables

You can override Redis host/port:

export REDIS_HOST=localhost
export REDIS_PORT=6379


⸻

Quick Manual Test

After starting Redis and the Netty server:

# Health check
curl http://localhost:8080/health
# -> {"status":"UP"}

# Dashboard (first call – cache MISS, external APIs are called)
curl http://localhost:8080/api/dashboard

# Dashboard (subsequent calls within TTL – cache HIT)
curl http://localhost:8080/api/dashboard


⸻

Running via Docker (example)

If you have a Dockerfile in the project, a typical flow looks like this:

# Build JAR
mvn clean package

# Build Docker image
docker build -t dashboard-aggregator .

# Run together with Redis
docker network create dashboard-net || true

docker run -d --rm \
--name redis \
--network dashboard-net \
redis:7-alpine

docker run -d --rm \
--name dashboard-aggregator \
--network dashboard-net \
-e REDIS_HOST=redis \
-e REDIS_PORT=6379 \
-p 8080:8080 \
dashboard-aggregator

Then access:

curl http://localhost:8080/health
curl http://localhost:8080/api/dashboard

⸻

Notes
•	All external calls are asynchronous and non-blocking.
•	Redis write failures do not break the main dashboard response – the service still returns fresh data.
•	The design is interface-driven (ExternalApi, RedisCache) for better testability and future extensions.

Jerzy Miskiewicz