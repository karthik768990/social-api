# Social API — Redis Guardrails Microservice

A Spring Boot 3.x microservice acting as a central API gateway with a Redis-backed virality engine, atomic concurrency guardrails, and a smart notification batching system.

---

## Tech Stack

- **Java 17** + **Spring Boot 3.2**
- **PostgreSQL 16** — source of truth for all content
- **Redis 7** — gatekeeper for all counters, cooldowns, and notifications
- **Spring Data Redis** — Lua script execution via `RedisTemplate`
- **Docker Compose** — one-command local setup

---

## Quick Start

```bash
# 1. Start PostgreSQL and Redis
docker-compose up -d

# 2. Build and run the app
./mvnw spring-boot:run

# 3. Import postman_collection.json into Postman and start testing
```

The app runs on `http://localhost:8080`.

---

## Architecture Overview

```
Client Request
      │
      ▼
 PostController / CommentController
      │
      ▼
 CommentService (business logic)
      │
      ├──► RedisGuardrailService  ◄── Lua scripts (atomic)
      │         │
      │         ├── Virality score  (INCR)
      │         ├── Horizontal cap  (check+INCR via Lua)
      │         ├── Vertical cap    (in-memory, from request)
      │         ├── Cooldown cap    (SET NX EX via Lua)
      │         └── Notification    (LIST + TTL key via Lua)
      │
      └──► CommentRepository  ──► PostgreSQL
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/users` | Create a user |
| `POST` | `/api/bots` | Create a bot |
| `POST` | `/api/posts` | Create a post |
| `GET`  | `/api/posts/{postId}` | Get post + virality score |
| `POST` | `/api/posts/{postId}/comments` | Add a comment (guardrails applied for bots) |
| `POST` | `/api/posts/{postId}/like` | Like a post (+20 virality) |

### Bot Comment Request Body

```json
{
  "authorId": 1,
  "authorType": "BOT",
  "content": "Nice post!",
  "depthLevel": 1,
  "targetUserId": 42
}
```

`targetUserId` is the human user the bot is interacting with — required for cooldown tracking.

---

## Phase 2: How Thread Safety Is Guaranteed

This is the critical section. All atomic operations use **Lua scripts executed via Redis `EVAL`**.

### Why Lua Scripts and Not Java Locks or Redis Transactions?

| Approach | Problem |
|---|---|
| `synchronized` / Java locks | Breaks statelessness; state lives in one JVM only |
| `HashMap` / static fields | Breaks statelessness; reset on restart |
| Redis `WATCH` + `MULTI/EXEC` | Optimistic locking — retries on conflict, causes thundering herd under 200 concurrent requests |
| **Lua scripts via `EVAL`** | ✅ Atomic on the Redis server; single-threaded Redis event loop guarantees no interleaving |

### Guardrail 1: Horizontal Cap (max 100 bot replies per post)

**Redis key:** `post:{id}:bot_count`

```lua
-- Atomically check and increment in a single round-trip
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current >= tonumber(ARGV[1]) then
  return 0          -- REJECTED: at or over cap
end
return redis.call('INCR', KEYS[1])  -- ALLOWED: counter incremented
```

Under 200 concurrent requests, all 200 threads call this script simultaneously. Redis executes them **serially** (single-threaded event loop). The 101st request will always see `current = 100` and return 0. The database write only happens after this script returns a positive value. **It is mathematically impossible for the counter to exceed 100.**

If the subsequent DB write fails, we call `DECR` to roll back the Redis counter, maintaining consistency.

### Guardrail 2: Cooldown Cap (bot → human, 10-minute TTL)

**Redis key:** `cooldown:bot_{id}:human_{id}` with 600s TTL

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0   -- already cooling down
end
redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
return 1     -- cooldown started, interaction allowed
```

The check and the set happen in the same atomic script, eliminating the race condition where two concurrent requests both see "key doesn't exist" and both proceed.

### Guardrail 3: Vertical Cap (max thread depth 20)

Depth is supplied in the request payload and checked in Java before hitting Redis. No Redis key needed — purely deterministic from the request.

---

## Phase 1: Virality Score

| Interaction | Points |
|---|---|
| Bot Reply | +1 |
| Human Like | +20 |
| Human Comment | +50 |

**Redis key:** `post:{id}:virality_score`

Uses Redis `INCRBY` — atomic by definition. Virality scores are returned alongside post data in `GET /api/posts/{id}`.

---

## Phase 3: Notification Engine

**Immediate send path** (no active cooldown):
```
[NOTIFICATION] Push Notification Sent to User 42: Bot 'AlphaBot' replied to post 7
```

**Batched path** (within 15-minute cooldown window):
- Message is pushed to `user:{id}:pending_notifs` (Redis List)

**CRON Sweeper** runs every 5 minutes:
```
[CRON SWEEPER] Starting pending notification sweep...
[NOTIFICATION] Summarized Push Notification to User 42: Bot 'AlphaBot' replied to your post and [3] others interacted with your posts.
[CRON SWEEPER] Sweep complete.
```

The pop operation uses a Lua script (LRANGE + DEL atomically) so no messages are lost if the sweeper runs concurrently with a new notification being enqueued.

---

## Phase 4: Race Condition Test

To reproduce the 200-concurrent-bot spam test:

```bash
# Using Apache Bench (ensure 200 bots exist in DB first)
# Or use Postman Collection Runner with 200 iterations

# With curl and parallel (Linux):
seq 1 200 | xargs -P 200 -I{} curl -s -X POST http://localhost:8080/api/posts/1/comments \
  -H "Content-Type: application/json" \
  -d "{\"authorId\":{},\"authorType\":\"BOT\",\"content\":\"Bot {} here\",\"depthLevel\":1,\"targetUserId\":1}"

# After this, verify in PostgreSQL:
# SELECT COUNT(*) FROM comments WHERE post_id = 1 AND author_type = 'BOT';
# Expected: exactly 100
```

---

## Redis Key Reference

| Key Pattern | Type | Purpose | TTL |
|---|---|---|---|
| `post:{id}:virality_score` | String | Accumulated virality points | None |
| `post:{id}:bot_count` | String | Bot reply counter | None |
| `cooldown:bot_{id}:human_{id}` | String | Per-pair cooldown flag | 10 min |
| `notif_cooldown:user_{id}` | String | Notification rate limit | 15 min |
| `user:{id}:pending_notifs` | List | Batched notification queue | None (cleared by CRON) |

---

## Statelessness Guarantee

- **No `HashMap`, `ConcurrentHashMap`, or `static` variables** are used anywhere in the application.
- **No in-memory counters** — every counter lives in Redis.
- The Spring Boot application can be scaled horizontally to N instances; all instances share the same Redis, so guardrails remain consistent across the cluster.
- All `@Scheduled` tasks use Spring's thread pool scheduler, which is also stateless (state is in Redis).
