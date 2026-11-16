# Real-Time State Propagation

## Purpose

This document describes how domain state changes flow from the application layer to connected frontend clients via RabbitMQ, WebSocket, and HTTP polling — and where the propagation chain is currently incomplete.

## Overview

The city assistant application supports multiple clients that must see consistent, up-to-date city data in real time. Three independent mutation paths exist:

1. **Dashboard CRUD** — Direct HTTP mutation via the React frontend (POST/PUT/DELETE `/demo/cities`)
2. **Agent CRUD** — LangChain4j tool calls that enqueue changes to RabbitMQ (createCity, updateCity, deleteCity, updateCities)
3. **Domain Operations** — High-level atomic operations executed directly by CityService (updateAllCities, undoLastOperation, createSnapshot)

All three mutation paths modify the same PostgreSQL `cities` table and the Elasticsearch index. The question is: **how does every connected client learn about these changes?**

---

## Current Propagation Chain (Working)

### Dashboard CRUD → RabbitMQ → WebSocket → Frontend

```
React UI ──POST/PUT/DELETE──► Ktor Route
                            │
                            ├─ requirePermission("auth-session")
                            │
                            ├─ enqueueChange(CityCommand)
                            │        │
                            │        ▼
                            │   CityCommandPublisher
                            │        │
                            │        ▼
                            │   RabbitMQ (test-exchange → test-queue)
                            │        │
                            │        ▼
                            │   RabbitMQ Consumer
                            │        │
                            │        ▼
                            │   CityChangeProcessor.apply()
                            │        │
                            │        ├─ Persist to PostgreSQL
                            │        ├─ Reindex Elasticsearch
                            │        └─ ConsumerState.onMessage("city-changed")
                            │                 │
                            │                 ▼
                            │         ConsumerState.messageChannel
                            │                 │
                            │                 ▼
                            │         WebSocket broadcast loop
                            │                 │
                            │                 ▼
                            │         WebSocketClient (all connected)
                            │                 │
                            │                 ▼
                            │         useMessages() hook
                            │                 │
                            │                 ▼
                            │         loadCities() — refetch
                            │                 │
                            ▼                 ▼
                        UI refreshes        UI shows city-changed message
```

**What works:**
- Every dashboard mutation publishes a `CITY_CHANGE` message via RabbitMQ
- The consumer persists it, calls `ConsumerState.onMessage("city-changed")`, which broadcasts through WebSocket to all clients
- The frontend `useMessages` hook detects `"city-changed"` and calls `loadCities()`, refetching the full city list

### Agent CRUD → RabbitMQ → Same chain as above

Agent tools (`createCity`, `updateCity`, `deleteCity`, `updateCities`) follow the identical path:

```
LLM tool call → enqueueChange(CityCommand) → RabbitMQ → same chain as above ✓
```

---

## Current Propagation Gap (Broken)

### Domain Operations → No Propagation

```
LLM domain tool call → CityService.updateAllPopulation(delta)
                       CityService.undoLastOperation()
                       CityService.createSnapshot(name)
                             │
                             ├─ Persist to PostgreSQL
                             ├─ Elasticsearch reindex (partial — only after individual CRUD)
                             └─ [MISSING] ConsumerState.onMessage("city-changed")
                                      │
                                      ▼
                              No WebSocket notification
                                      │
                                      ▼
                              Clients see stale data
```

**Why it's broken:** Domain operation tools (`updateAllCities`, `undoLastOperation`, `createSnapshot`) execute atomically within the application. They call `cityService.updateAllPopulation()` or `cityService.undoLastOperation()` directly — bypassing RabbitMQ entirely. Unlike CRUD tools, which enqueue a `CityCommand` that travels through the message broker, domain operations modify the database in-memory without broadcasting.

**Result:** When the LLM applies "add 50 to each city", the database is updated correctly, but all other connected clients see stale data until they manually refresh or poll.

---

## What Should Be Propagated

Every mutation to city state should reach connected clients. The following operations and their propagation status:

| Operation | Path | Currently Propagated? |
|-----------|------|-----------------------|
| Dashboard: createCity | HTTP → RabbitMQ → WS | ✅ Yes |
| Dashboard: updateCity | HTTP → RabbitMQ → WS | ✅ Yes |
| Dashboard: deleteCity | HTTP → RabbitMQ → WS | ✅ Yes |
| Agent: createCity | Tool → RabbitMQ → WS | ✅ Yes |
| Agent: updateCity | Tool → RabbitMQ → WS | ✅ Yes |
| Agent: deleteCity | Tool → RabbitMQ → WS | ✅ Yes |
| Agent: updateCities (batch) | Tool → RabbitMQ → WS | ✅ Yes |
| **Agent: updateAllCities (bulk)** | **Tool → direct DB** | ❌ **No** |
| **Agent: undoLastOperation** | **Tool → direct DB** | ❌ **No** |
| **Agent: createSnapshot** | **Tool → direct DB** | ❌ **No** |

---

## Design Principles

### 1. Single Source of Truth for Notifications

The `ConsumerState.onMessage(body)` function is the **only** entry point for notifying clients. Any code path that mutates city state must call it.

Currently, `CityChangeProcessor.apply()` — the single point that persists CRUD commands — calls `ConsumerState.onMessage("city-changed")` at line 101. Domain operations bypass this entirely.

### 2. Structured Change Events

A generic `"city-changed"` string forces the frontend to do a full refetch. For richer UX, we need structured events that convey:

- **What changed** (create, update, delete, update_all, undo, snapshot)
- **How many entities** were affected
- **Who initiated** it (user, agent conversation)
- **Before/after values** (for undo/snapshot restore)

```kotlin
@Serializable
data class CityChangedEvent(
    val event: String = "city-changed",
    val operation: String,          // "create", "update", "delete", "update_all", "undo", "snapshot"
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

### 3. Operation History Integration

Operation history is already persisted in the `city_operations` table (see `docs/solutions/domain-operation-design.md`). Every mutation — including domain operations — should record an operation record, and the `ConsumerState.onMessage()` call should carry the operation's structured metadata.

### 4. Frontend Event Handling

The frontend's `useMessages` hook in `frontend/src/hooks/useMessages.ts` currently:
- Monitors for `"city-changed"` and triggers a full city refetch
- Appends all other messages to the RabbitMQ messages display

After structured events are introduced, the frontend should:
- Display structured change notifications (e.g., "London: 13,961,300 → 13,961,350")
- Show operation history events in a dedicated section
- Handle `"auth-required"` via existing WebSocket close code 1008 handling

---

## Proposed Architecture

### Step 1: Make Domain Operations Emit Events

After each domain operation completes in `CityService` or `CityAgentTools`, call `ConsumerState.onMessage()` with a structured JSON payload instead of the generic `"city-changed"` string.

In `CityAgent.kt` domain tools:

```kotlin
fun updateAllCities(delta: Int, @ToolMemoryId conversationId: String): String {
    return runCatching {
        val result = cityService.updateAllPopulation(delta)
        val actor = executionContexts.getValue(conversationId)
        
        // Emit structured event for WebSocket broadcast
        val changes = result.changes.map { c ->
            CityChangeDetail(c.id, c.name, c.beforePopulation, c.afterPopulation)
        }
        val event = CityChangedEvent(
            operation = "update_all",
            conversationId = conversationId,
            actorId = actor.id,
            affectedCount = result.affectedCount,
            changes = changes,
        )
        ConsumerState.onMessage(Json.encodeToString(event))
        
        // ... format and return response text
    }.getOrElse { ... }
}
```

### Step 2: Add Event-Specific WebSocket Handling

The existing WebSocket loop in `Websocket.kt` (line 67–82) broadcasts `ConsumerState.messages` to all connected clients. This already works for any structured JSON message — no change needed to the WebSocket server.

### Step 3: Enhance Frontend Event Handling

In `frontend/src/hooks/useMessages.ts`, parse structured events and handle each operation type:

```typescript
socket.onmessage = (event) => {
  try {
    const message = JSON.parse(event.data) as WsMessage
    
    // Check if this is a structured city-changed event
    const parsed = JSON.parse(message.body)
    if (parsed.event === 'city-changed' && parsed.operation) {
      switch (parsed.operation) {
        case 'update_all':
          displayBulkUpdateNotification(parsed)
          break
        case 'undo':
          displayUndoNotification(parsed)
          break
        case 'snapshot':
          displaySnapshotNotification(parsed)
          break
        case 'create':
        case 'update':
        case 'delete':
          displayIndividualChangeNotification(parsed)
          break
      }
      onCityChanged?.()  // Still trigger refetch
    } else if (typeof message.body === 'string') {
      setMessages((current) => [...current, message])
    }
  } catch {
    // Ignore malformed messages
  }
}
```

### Step 4: Auth State Propagation

The WebSocket already handles auth expiration via:
- Server-side: `authenticationService.resolve()` check every 30s (line 42–49 in Websocket.kt)
- Server-side: `authenticationService.resolve(socket.session)?.id != socket.user.id` before each broadcast (line 72)
- Frontend: Closes with code 1008 → triggers `app-auth-required` event

This auth chain is **working correctly**. No changes needed here.

---

## Additional Propagation Opportunities

Beyond the core city state changes, the following events would improve UX if propagated:

| Event | Value | Implementation |
|-------|-------|----------------|
| `operation-completed` | Shows confirmation of bulk operation with affected count | Emit from domain operation tools |
| `auth-expired` | Explicitly notifies client before WebSocket drops | Server sends before closing with 1008 |
| `operation-history-changed` | Client can update an operation history panel | Emit from `CityChangeProcessor` |
| `agent-status` | Tells client the agent is processing/responding | Existing SSE handles this |
| `login-success` / `login-failure` | Reacts to auth state changes | Existing polling + API error handling covers this |

---

## Summary

| Concern | Current State | Required Action |
|---------|---------------|-----------------|
| Dashboard CRUD propagation | ✅ Full chain via RabbitMQ → WS → Frontend | None |
| Agent CRUD propagation | ✅ Full chain via RabbitMQ → WS → Frontend | None |
| Domain operation propagation | ❌ DB modified, WS never notified | Emit structured `CityChangedEvent` from domain tools |
| Auth state propagation | ✅ WebSocket close code 1008 + frontend polling | None |
| Structured change display | ❌ Frontend only triggers full refetch | Add structured event parsing in `useMessages` |
| Operation history sync | ❌ No real-time sync of operation history | Add `operation-history-changed` event emission |
