# Auth State and Real-Time Propagation Issues

## Problem Overview

Two related problems cause the city list to appear empty or stale after user interactions:

### Issue 1: Empty city list after successful login

After a user successfully logs in, the city list on the dashboard remains empty. Reloading the page in the browser correctly loads the cities. The root cause is that the WebSocket connection (`useMessages` hook) was established before the user authenticated, so no `city-changed` event is triggered by login. The initial `loadCities()` call in the component's `useEffect` may also fail if authentication state hasn't fully propagated yet.

**User-facing symptom:** The UI says "Logged in as admin" but shows zero cities.

### Issue 2: Multi-operation updates don't propagate

When the agent processes "add 100 to each city" (a bulk/aggregate operation), the agent returns a success response, but connected clients do not see the updated city list. Individual city updates (e.g., "change Paris to 3000000") propagate correctly.

**User-facing symptom:** The agent confirms changes were applied, but the city grid shows old/stale data.

## Root Cause Analysis

### Issue 1: Missing post-login city reload

**Flow when the user logs in:**

1. `AuthContext.tsx` calls `login(username, password)` → API returns `AuthenticatedUser`
2. `AuthContext` sets `user` state → React re-renders `App`
3. `useMessages(onCityChanged, enabled)` re-evaluates:
   - `enabled` transitions from `false` (unauthenticated) → `true` (authenticated)
   - Effect cleanup: old WebSocket closes, new one opens
   - On `socket.onopen`: `onCityChanged` callback fires → `loadCities()`
4. However, there is a race window: the `loadCities()` effect in `App` (line 78-80) runs on mount, but if authentication is still loading at that point, the `/demo/cities` endpoint returns 401, and the error is silently swallowed. After login, the effect does not re-run because `loadCities` (wrapped in `useCallback`) hasn't changed its reference.

**Result:** The initial `useEffect` silently fails, and the `onCityChanged` callback on WebSocket reconnect is the only chance to load cities — but it depends on the WebSocket connecting successfully after the auth state transition.

### Issue 2: Propagation difference between CRUD and domain operations

Both CRUD and domain operations call `ConsumerState.onMessage()`, which broadcasts through the WebSocket to all connected clients. The frontend `useMessages` hook checks `message.body.includes('city-changed')` and triggers `loadCities()`.

**CRUD path (works):**
```
enqueueChange(CityCommand)
  → RabbitMQ → RabbitMQ Consumer → CityChangeProcessor.apply()
    → DB persist + ConsumerState.onMessage("city-changed")
      → WebSocket broadcast → frontend "city-changed" → loadCities()
```

**Domain operation path (broken for bulk):**
```
cityService.updateAllPopulation(delta)
  → ConsumerState.onMessage(Json.encodeToString(CityChangedEvent{operation: "update_all", ...}))
    → WebSocket broadcast → frontend receives structured event → loadCities()
```

Both paths call `ConsumerState.onMessage()`, but there are key differences:

| Aspect | CRUD path | Domain op path |
|--------|-----------|----------------|
| Message format | Plain string `"city-changed"` | Structured JSON `{"event":"city-changed","operation":"update_all",...}` |
| Serialization | `WsMessage(body)` = `WsMessage("city-changed")` | `WsMessage(body)` = `WsMessage("{\"event\":\"city-changed\",...}")` |
| Frontend check | `message.body.includes('city-changed')` → `true` | `message.body.includes('city-changed')` → `true` (within JSON) |
| Execution context | After RabbitMQ consumer processes | During SSE streaming response |

The messages reach the frontend in identical format (JSON with a `body` field). The `includes()` check passes for both. The mechanism itself should work.

**Likely culprit:** A race condition where the SSE response stream is still being written when the WebSocket message is broadcast. The broadcast loop may fail to send (the client-side SSE processing blocks event loop), or the message is silently dropped due to an unhandled exception in the broadcast catch block.

Additionally, the `CityChangedEvent` serialization may include unexpected formatting (e.g., non-breaking spaces in city names, or the `event` field not matching the expected substring in certain edge cases).

## Impact

- After every login/logout cycle, the city list is invisible until the user manually reloads
- Bulk operations (updateAllCities, undoLastOperation) create changes that only the initiating client sees after a manual reload
- Connected clients have divergent city data, causing confusion and inconsistent state

## Design Principles

1. **Authentication changes must trigger explicit data reloads** — relying on WebSocket reconnection to load initial data is fragile
2. **Structured events should always include a reload trigger** — `city-changed` must always be present in the serialized output regardless of operation type
3. **Every mutation path must be traceable** — if `onMessage` is called, the broadcast must be verified
