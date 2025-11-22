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
| **Fault tolerance (fallback)** | Cached values returned when API requests fail |
| **Single endpoint** | `GET /api/dashboard` |

---

## 📦 Requirements

| Component | Version |
|----------|---------|
| Java | 17+ (tested on Temurin 21) |
| Maven | 3.8+ |
| Redis | running locally on `localhost:6379` |
| macOS/Linux/Windows | any |

Make sure Redis is running before starting the server:

```bash
brew services start redis
redis-cli ping

Expected output:

PONG


⸻

📂 Project Structure

src/
 ├─ main/java/com/jerzymiskiewicz/dashboard
 │   ├─ NettyServer.java               # Application entry point
 │   ├─ HttpServerInitializer.java     # Netty pipeline configuration
 │   ├─ DashboardHandler.java          # HTTP request handler
 │   └─ service
 │        ├─ DashboardService.java     # Aggregates external data + caching + fallback
 │        ├─ ExternalApiClient.java    # Calls external HTTP APIs asynchronously
 │        └─ RedisCache.java           # Async Redis operations (Lettuce)
 │
 └─ test/java/com/jerzymiskiewicz/dashboard/service
      ├─ ExternalApiClientTest.java    # Mocks HTTP and verifies error handling
      ├─ DashboardServiceTest.java     # Tests aggregation + fallback logic
      └─ RedisCacheTest.java           # Integration test with real Redis


⸻

▶ How to Build & Run

1. Build

mvn clean package

2. Run

java -cp target/classes com.jerzymiskiewicz.dashboard.NettyServer

The server starts at:

http://localhost:8080/api/dashboard


⸻

🧪 Testing Fallback Behavior (Manual)

Step 1 — Generate cached data

curl http://localhost:8080/api/dashboard

Verify stored JSON:

redis-cli get "dashboard:lastSuccess"

Step 2 — Simulate API failure

Edit ExternalApiClient.java:

String url = "https://uselessfacts.jsph.pl/api/v2/facts/randomXXX"; // intentionally broken

Restart server and call:

curl http://localhost:8080/api/dashboard

Expected behavior:
	•	No new fact fetched
	•	JSON returned from Redis instead

⸻

🧪 Automated Tests

This project contains meaningful tests that validate core behavior:

Component	What is tested	Type
ExternalApiClient	Fails the future when response status is non-2xx	Unit (mocked HTTP client)
DashboardService	Aggregation + saving to Redis + fallback on failure	Unit (mock API + mock Redis)
RedisCache	Writing & reading from a real Redis instance	Integration

Key guarantees:
	•	External API errors do not produce partial data
	•	Fresh data is persisted with TTL
	•	When any request fails, cached JSON is returned

Run tests:

mvn test

Ensure Redis is active:

brew services start redis
redis-cli ping


⸻

📘 How It Works (Architecture)
	1.	DashboardHandler receives HTTP GET request.
	2.	DashboardService triggers 3 asynchronous external calls in parallel.
	3.	CompletableFuture.allOf() waits for completion.
	4.	If successful:
	•	Responses are merged
	•	JSON is stored in Redis
	5.	If any API fails:
	•	Cached result from Redis is returned instead

No blocking calls are made inside Netty event loop.

👤 Author

Jerzy Miskiewicz
Java Developer