# Dashboard Aggregator (Netty + CompletableFuture + Redis)

A non-blocking HTTP server built using **Netty** that aggregates data from three external APIs and returns a single JSON response.

The server:
- fetches **current weather** from Open-Meteo
- fetches a **random fact** from uselessfacts API
- fetches **public IP** from ipify
- stores the last successful response in **Redis**
- returns cached data if any service fails (fallback mechanism)

---

## 🚀 Features

| Feature | Description |
|--------|------------|
| **Non-blocking IO** | Built using Netty event loops |
| **Parallel external calls** | Uses `CompletableFuture` to fetch data concurrently |
| **Redis caching** | Stores last successful JSON response |
| **Fault tolerance (fallback + cache-first)** | Cached values returned when available, or on failures |
| **Single endpoint** | `GET /api/dashboard` |

---

## 📦 Requirements

| Component | Version |
|----------|---------|
| Java | 17+ (tested on Temurin 21) |
| Maven | 3.8+ |
| Redis | running on `HOST:PORT` (defaults: `localhost:6379`) |
| OS | Linux/macOS/Windows |

Redis can be provided:
- locally (installed on host),
- or via Docker / Docker Compose.

---

## 📂 Project Structure

```text
src/
 ├─ main/java/com/jerzymiskiewicz/dashboard
 │   ├─ NettyServer.java               # Application entry point
 │   ├─ HttpServerInitializer.java     # Netty pipeline configuration
 │   ├─ DashboardHandler.java          # HTTP request handler
 │   └─ service
 │        ├─ DashboardService.java     # Cache-first aggregation + fallback logic
 │        ├─ ExternalApiClient.java    # Async external HTTP API calls
 │        └─ RedisCache.java           # Async Redis operations (Lettuce)
 │
 └─ test/java/com/jerzymiskiewicz/dashboard/service
      ├─ ExternalApiClientTest.java    # Mocks HTTP and verifies error handling
      ├─ DashboardServiceTest.java     # Tests cache hit/miss and aggregation
      └─ RedisCacheTest.java           # Integration-style test for Redis


⸻

▶ How to Build & Run (without Docker)

1. Install Redis

On macOS (Homebrew):

brew install redis
brew services start redis

On Ubuntu/Debian:

sudo apt update
sudo apt install redis-server
sudo systemctl enable redis-server
sudo systemctl start redis-server

Check:

redis-cli ping
# → PONG


⸻

2. Build the project

mvn clean package

Build does not require Redis to be running — integration test is skipped if Redis is unavailable.

⸻

3. Run the server

java -cp target/classes com.jerzymiskiewicz.dashboard.NettyServer

The server will start at:

http://localhost:8080/api/dashboard


⸻

🐳 Run with Docker Compose (recommended for Linux)

You can also run the app together with Redis using Docker Compose.

1. Build & start services

docker compose up --build

This will:
	•	start redis service (Redis 7),
	•	build and start app service (Netty server),
	•	expose port 8080 on the host.

2. Call the endpoint

curl http://localhost:8080/api/dashboard


⸻

🧪 Testing Fallback & Cache Behavior (manually)

Step 1 — Generate cached data

With server running:

curl http://localhost:8080/api/dashboard

Check saved cache in Redis:

redis-cli get "dashboard:lastSuccess"


⸻

Step 2 — Simulate API failure

Edit ExternalApiClient.java:

private static final String RANDOM_FACT_URL = "https://uselessfacts.jsph.pl/api/v2/facts/randomXXX"; // intentionally broken

Rebuild / restart the app, then call:

curl http://localhost:8080/api/dashboard

Expected behavior:
	•	external fact API will fail,
	•	app falls back to existing cached JSON (or fails if cache is empty).

⸻

🧪 Automated Tests

This project contains tests that validate core behavior:

Component	What is tested	Type
ExternalApiClient	Future completes exceptionally on non-2xx HTTP status	Unit (mocked HttpClient)
DashboardService	Cache hit (no external calls) and cache miss (aggregate + save to Redis)	Unit (mock API + mock Redis)
RedisCache	Save & get value from Redis	Integration test (skipped if Redis is not available)

Run tests:

mvn test


⸻

📘 How It Works (Architecture)
	1.	DashboardHandler receives an HTTP GET /api/dashboard request.
	2.	DashboardService:
	•	first tries to get JSON from Redis (dashboard:lastSuccess),
	•	on cache hit: immediately returns cached JSON,
	•	on cache miss: triggers 3 async API calls (weather, fact, IP) in parallel.
	3.	CompletableFuture.allOf() waits for completion of all API calls.
	4.	If all succeed:
	•	responses are merged into a single JSON,
	•	result is stored in Redis with TTL.
	5.	If APIs fail while cache is empty:
	•	an error is returned (there is nothing to fall back to).

No blocking calls are made in Netty event loop – all external calls use CompletableFuture and async HTTP client.

Jerzy Miskiewicz