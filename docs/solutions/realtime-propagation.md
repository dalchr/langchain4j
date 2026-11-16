# Real-Time State Propagation

## Purpose

This document describes how domain state changes flow from the application layer to connected frontend clients, the mechanisms that enable it, and the bugs that were discovered and fixed during implementation. It serves as a technical reference for troubleshooting city-state sync, SSE streaming, and auth propagation issues.

---

## Overview

The city assistant application supports multiple clients that must see consistent, up-to-date city data in real time. Three independent mutation paths exist:

1. **Dashboard CRUD** — Direct HTTP mutation via the React frontend (POST/PUT/DELETE `/demo/cities`)
2. **Agent CRUD** — LangChain4j tool calls that enqueue changes to RabbitMQ (`createCity`, `updateCity`, `deleteCity`, `updateCities`)
3. **Domain Operations** — High-level atomic operations executed directly by `CityService` (`updateAllCities`, `undoLastOperation`, `createSnapshot`)

All three mutation paths modify the same PostgreSQL `cities` table. The question is: **how does every connected client learn about these changes?**

---

## Complete Propagation Chain (Working)

Every mutation — regardless of origin — now flows through the same broadcast entry point:

```
┌──────────────────────────────────────────────────────┐
│ Mutation Origin                                       │
├──────────┬──────────┬────────────────────────────────┤
│ Dashboard│ Agent CRUD│ Domain Operation              │
│ CRUD     │           │                                │
└────┬─────┴─────┬─────┴──────────┬─────────────────────┘
     │           │                 │
     ▼           ▼                 ▼
  enqueueChange   │          cityService.*()
  (via HTTP)      │                 │
     │           │                 │
     ▼           ▼                 ▼
  RabbitMQ     │          CityAgentTools.* tool
  publish       │          (updateAllCities,
     │           │           undoLastOperation,
     ▼           ▼           createSnapshot)
  Consumer      │              │
  processes     │              │
     │           │              ▼
     ▼           ▼       DB mutation (atomic)
  CityChangeProcessor.apply()
     │
     ▼
  DB persist + Elasticsearch reindex
     │
     ▼
  ConsumerState.onMessage(body: String)
     │
     ▼
  ConsumerState.messageChannel (Ktor Channel<WsMessage>)
     │
     ▼
  WebSocket broadcast loop (Websocket.kt)
     │
     ▼
  All connected WebSocketClient sessions
     │
     ▼
  frontend/src/hooks/useMessages.ts
     │
     ▼
  onCityChanged callback → loadCities() refetch
     │
     ▼
  UI grid refreshes with fresh data
```

**What works today:**

- Every mutation path calls `ConsumerState.onMessage()` with a structured JSON payload.
- The `ConsumerState.messageChannel` is a unbounded Ktor `Channel<WsMessage>` — no message loss under normal load.
- The WebSocket broadcast loop iterates `ConsumerState.messages` and sends every message to all connected sessions.
- The frontend `useMessages` hook receives messages on the WebSocket and triggers `loadCities()` to refetch the city grid.

---

## Mutation Paths in Detail

### 1. Dashboard CRUD

```
React POST/PUT/DELETE → Ktor route (authenticated)
  → enqueueChange(CityCommand)
  → RabbitMQ exchange → queue
  → RabbitMQ Consumer
  → CityChangeProcessor.apply()
  → DB persist + ES reindex + ConsumerState.onMessage("city-changed")
  → WebSocket broadcast → frontend refetch
```

### 2. Agent CRUD (single/batch)

```
LLM tool call (createCity, updateCity, deleteCity, updateCities)
  → CityAgentTools.* method
  → checkAuthorization → executionContext lookup
  → enqueueChange(CityCommand)
  → RabbitMQ → same chain as Dashboard CRUD ✓
```

### 3. Domain Operations (bulk/atomic)

```
LLM tool call (updateAllCities, undoLastOperation, createSnapshot)
  → CityAgentTools.* method
  → checkAuthorization → executionContext lookup
  → cityService.*()  ← direct DB mutation (no RabbitMQ)
  → build CityChangedEvent with operation, changes, affectedCount
  → ConsumerState.onMessage(Json.encodeToString(event))
  → WebSocket broadcast → frontend refetch
```

**Key difference:** Domain operations bypass RabbitMQ because they execute atomically within a single transaction. There is no need for async message processing — the event is emitted synchronously after the DB commit.

---

## Structured Event Format

Domain operations emit a `CityChangedEvent` serialized to JSON via `kotlinx.serialization`:

```kotlin
@Serializable
data class CityChangedEvent(
    val event: String = "city-changed",
    val operation: String,          // "update_all", "undo", "snapshot"
    val conversationId: String? = null,
    val actorId: String? = null,
    val affectedCount: Int = 0,
    val changes: List<CityChangeDetail> = emptyList(),
)

@Serializable
data class CityChangeDetail(
    val id: Int,
    val name: String,
    val beforePopulation: Int,
    val afterPopulation: Int,
)
```

Example payload for `updateAllCities(50)`:

```json
{
  "event": "city-changed",
  "operation": "update_all",
  "actorId": "local:admin",
  "affectedCount": 5,
  "changes": [
    { "id": 1, "name": "Tokyo", "beforePopulation": 13961300, "afterPopulation": 13961350 },
    { "id": 2, "name": "Paris", "beforePopulation": 2161100, "afterPopulation": 2161150 }
  ]
}
```

---

## Frontend Event Handling

### Message Reception (`frontend/src/hooks/useMessages.ts`)

The `useMessages` hook receives WebSocket messages and classifies them:

```typescript
socket.onmessage = (event) => {
  try {
    const message = JSON.parse(event.data) as WsMessage
    const body = typeof message.body === 'string' ? message.body : JSON.stringify(message.body)

    try {
      const parsed = JSON.parse(body)
      // Structured event: has 'operation' field or event === 'city-changed'
      if (parsed.operation || parsed.event === 'city-changed') {
        onCityChanged?.()  // Triggers loadCities() refetch
      } else {
        setMessages(prev => [...prev, message])  // RabbitMQ messages
      }
    } catch {
      // Plain string message (e.g., legacy "city-changed")
      if (body.includes('city-changed')) {
        onCityChanged?.()
      } else {
        setMessages(prev => [...prev, message])
      }
    }
  } catch {
    // Ignore malformed messages, keep connection alive
  }
}
```

**Parsing strategy:**

1. First try to parse `message.body` as JSON.
2. If it's a structured event (has `operation` field or `event === 'city-changed'`), trigger city refetch.
3. Otherwise, append to the RabbitMQ message display.
4. If JSON parse fails, fall back to `body.includes('city-changed')` for backward compatibility with plain string messages.

### City Reload Stability (`frontend/src/app/App.tsx`)

The city reload callback is stabilized with a ref pattern:

```typescript
const cityReloadRef = useRef<() => void>(loadCities)
useEffect(() => { cityReloadRef.current = loadCities }, [loadCities])

const handleCityChanged = useCallback(() => {
  cityReloadRef.current()
}, [])

// Stable reference passed to useMessages — prevents unnecessary WebSocket reconnects
const { messages, connected } = useMessages(handleCityChanged, ...)
```

**Why this matters:** If `onCityChanged` is a new function reference on every render, `useMessages`'s `useEffect` cleanup fires, closing and reopening the WebSocket. This can drop incoming messages and cause the initial city list to be empty after login.

### Auth-Triggered City Reload

```typescript
// Reload cities whenever auth state changes (login, logout, session refresh)
useEffect(() => {
  if (!auth.loading && auth.user) {
    void loadCities()
  }
}, [auth.loading, auth.user, loadCities])
```

After successful login, `auth.user` transitions from `null` to `AuthenticatedUser`, triggering an immediate `loadCities()` call. This eliminates the "logged in but empty city list" problem.

---

## SSE Streaming and Keep-Alive

### The Problem

The LLM chat endpoint (`POST /demo/agent/chat/stream`) uses SSE to stream tokens back to the frontend. When the LLM takes more than ~10 seconds to generate its first token, the browser's `fetch` ReadableStream silently aborts the connection due to its implicit read timeout. This manifests on the server as:

```
WARN Application - Client disconnected during LLM stream for conversation {id} after 10002 ms
```

The server's `responseWriteTimeoutSeconds: 120` (configured in `application.yaml`) never triggers because the client already dropped the connection.

### How Keep-Alive Works

The streaming handler (`CityAgent.kt`, inside the `routes()` block) launches a background coroutine that writes keep-alive comments to the SSE stream every 5 seconds:

```kotlin
var firstTokenSent = false
val keepAliveJob = GlobalScope.launch(Dispatchers.IO) {
    try {
        while (true && !firstTokenSent) {
            delay(5000)
            if (!firstTokenSent) {
                writeStringUtf8(": keep-alive\n\n")
                flush()
            }
        }
    } catch (_: CancellationException) { }
    catch (_: Exception) { }
}

try {
    assistant.chat(conversationId, request.message)
        .onPartialResponse { partial ->
            responseText.append(partial)
            firstTokenSent = true           // ← stops keep-alive
            events.trySend(AgentStreamEvent("token", content = partial))
        }
        ...
} finally {
    keepAliveJob.cancel()                  // ← always clean up
    events.cancel()
}
```

**Critical detail — `flush()` must be called after `writeStringUtf8()`.** Without `flush()`, the keep-alive bytes sit in the `ByteWriteChannel` buffer and never reach the client. The 10-second browser timeout fires anyway. This was the root cause of the "keep-alive didn't work" debugging session.

**Why keep-alive comments work:** SSE comments (lines starting with `:`) do NOT fire the JavaScript `onmessage` callback. They are invisible to application code but reset the TCP stream's read timer in the browser's fetch implementation, preventing the 10-second abort.

### Server-Side Timeout Configuration

`application.yaml` sets `ktor.deployment.netty.config.responseWriteTimeoutSeconds: 120` to allow the server to keep the connection open for up to 2 minutes. The keep-alive coroutine (5s interval) is well below this threshold.

---

## WriteTimeoutException Handling

Even with keep-alive, long operations can still be interrupted (network issues, client navigation, etc.). The streaming handler's catch block detects `WriteTimeoutException` and `CancellationException`:

```kotlin
catch (error: Throwable) {
    val elapsedMs = (System.nanoTime() - requestStartedAt) / 1_000_000
    val isWriteTimeout = error::class.java.name == "io.netty.handler.timeout.WriteTimeoutException"
        || error.cause?.let { it::class.java.name == "io.netty.handler.timeout.WriteTimeoutException" } ?: false

    if (error is java.util.concurrent.CancellationException || isWriteTimeout) {
        log.warn("Client disconnected during LLM stream for conversation {} after {} ms",
                 request.conversationId, elapsedMs)
    } else {
        log.error("LLM chat stream failed for conversation {} after {} ms",
                  request.conversationId, elapsedMs, error)
    }
}
```

This logs a clean `WARN` with the conversation ID and elapsed time — no noisy stack trace.

---

## Auth State Propagation

### Server-Side Auth Check

Every agent route validates the user before processing:

- Route handlers use explicit `call.currentAuthenticatedUser()` with `WARN`/`INFO` logging (not silent `call.requirePermission`).
- Tool-level `checkAuthorization` throws `ToolErrorVisibleToLlmException` (logged as `WARN` with user/permission details) instead of returning error strings.

### WebSocket Auth

- Server-side auth check runs every 30 seconds (`Websocket.kt`, line 42–49).
- Server-side user validation runs before each broadcast (`Websocket.kt`, line 72).
- On auth failure: WebSocket closes with code 1008 → frontend triggers `app-auth-required` event.
- Auth polling in `AuthContext.tsx` (30s interval) keeps the frontend's auth state in sync.

### Auth State Change → City Reload

When auth state changes (login or logout), the effect in `App.tsx` calls `loadCities()`, ensuring the city grid is always populated after authentication.

---

## Operation History

Operation history is persisted in the `city_operations` PostgreSQL table (see `docs/solutions/domain-operation-design.md`). Every domain operation:

1. Executes the atomic DB mutation (e.g., `updateAllPopulation(delta)`).
2. Records the operation in `city_operations` via `cityService`.
3. Emits a structured `CityChangedEvent` via `ConsumerState.onMessage()`.
4. Returns a human-readable summary to the LLM.

The LLM can review operation history via the `listOperations()` and `operationHistory()` tools, which serve as the audit trail for all mutations.

---

## Troubleshooting Checklist

When city updates don't propagate to clients, follow these steps:

### 1. Check Server Logs

```
# Did the mutation execute?
grep -i "RabbitMQ city change persisted" server.log      # CRUD path
grep -i "updateAllPopulation\|undoLastOperation" server.log  # Domain ops

# Did the event reach the WebSocket?
grep -i "onMessage\|broadcast\|city-changed" server.log

# SSE keep-alive / timeout?
grep -i "keep-alive\|WriteTimeout\|Client disconnected" server.log
```

### 2. Verify Mutation Path

| Symptom | Check |
|---------|-------|
| CRUD works, domain ops don't | Domain tools must call `ConsumerState.onMessage()` — check `CityAgentTools.updateAllCities`, `undoLastOperation`, `createSnapshot` |
| Single city updates propagate but bulk doesn't | Bulk operations use domain tools, not CRUD tools — verify structured event emission |
| Nothing propagates | Check `ConsumerState.onMessage()` is wired to both RabbitMQ consumer and domain tools |

### 3. Check Frontend Message Handling

```javascript
// In browser DevTools console:
// Check if structured events are parsed correctly
// Look for: parsed.operation || parsed.event === 'city-changed' → triggers onCityChanged
```

### 4. SSE Timeout

If the client disconnects after ~10 seconds:
- Verify `writeStringUtf8(": keep-alive\n\n")` is followed by `flush()`.
- Verify `firstTokenSent = true` is set inside `onPartialResponse`.
- Verify `keepAliveJob.cancel()` is in the `finally` block.

### 5. Empty City List After Login

- Check that `auth.user` is not `null` after login.
- Check that the auth-triggered `useEffect` fires (watch for `loadCities` network call in DevTools).
- Check that the WebSocket `onCityChanged` callback fires on connection (check for stable reference in `useMessages`).

---

## Complete Operation Matrix

| Operation | Origin | Mutation Path | Event Emitted? | WebSocket Propagated? |
|-----------|--------|---------------|----------------|----------------------|
| Dashboard: create | HTTP POST | → RabbitMQ → Consumer → DB → `onMessage` | ✅ | ✅ |
| Dashboard: update | HTTP PUT | → RabbitMQ → Consumer → DB → `onMessage` | ✅ | ✅ |
| Dashboard: delete | HTTP DELETE | → RabbitMQ → Consumer → DB → `onMessage` | ✅ | ✅ |
| Agent: createCity | LLM tool | → enqueueChange → RabbitMQ → same chain | ✅ | ✅ |
| Agent: updateCity | LLM tool | → enqueueChange → RabbitMQ → same chain | ✅ | ✅ |
| Agent: deleteCity | LLM tool | → enqueueChange → RabbitMQ → same chain | ✅ | ✅ |
| Agent: updateCities (batch) | LLM tool | → enqueueChange ×N → RabbitMQ → same chain | ✅ | ✅ |
| **Agent: updateAllCities** | LLM tool | → `cityService.updateAllPopulation` → DB → `onMessage` | ✅ JSON event | ✅ |
| **Agent: undoLastOperation** | LLM tool | → `cityService.undoLastOperation` → DB → `onMessage` | ✅ JSON event | ✅ |
| **Agent: createSnapshot** | LLM tool | → `cityService.createSnapshot` → DB → `onMessage` | ✅ JSON event | ✅ |

All operations now propagate. The key insight is that **every mutation path must call `ConsumerState.onMessage()`** — whether it arrives via RabbitMQ (CRUD) or directly from domain tools (bulk/atomic operations).

---

## Summary

| Concern | Status | Notes |
|---------|--------|-------|
| CRUD propagation (dashboard/agent) | ✅ Working | Via RabbitMQ → Consumer → `onMessage` |
| Domain operation propagation | ✅ Working | Direct `onMessage` with structured JSON |
| Frontend structured event parsing | ✅ Working | JSON parse → `operation` check → refetch |
| SSE keep-alive | ✅ Working | 5s interval with `flush()` |
| Auth-triggered city reload | ✅ Working | Effect on `auth.user` change |
| WriteTimeout graceful handling | ✅ Working | WARN log with conversation ID |
| Auth state propagation | ✅ Working | WebSocket 1008 + polling + explicit logging |
