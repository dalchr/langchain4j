# Analysis: Authentication Transparency for City Agent

## Problem Statement

When a user is logged out but the UI still shows "Logged in as admin", the system behaves as a **black box**:

1. **UI never reflects logout state** — the session cookie persists in the browser, the UI keeps showing the old user.
2. **Server logs provide no context** — auth check results are never logged, so there's no way to tell whether a request succeeded because the user was authorized or failed because they weren't.
3. **No recovery path** — when the agent refuses an operation, the user is stuck with an error message and no way to resume.

## Root Cause Analysis

### Issue 1: UI sync gap between frontend and backend

**How it works:**
- Login: Frontend receives session cookie from `/auth/login`. Backend stores session in `ConcurrentHashMap`. UI shows `auth.user.displayName`.
- Logout: Frontend calls `/auth/logout` → `sessions.remove(session.id)`. UI sets `auth.user = null`.
- Session expiry (8h): Browser cookie still present → requests carry expired cookie.

**What happens:**
- After logout/expiry, the browser cookie persists. The UI never receives a 401 response from the SSE route because the route-level auth check at `CityAgent.kt:466` (`call.requirePermission(Permission.CHAT_ACCESS)`) uses Ktor's `principal<AuthenticatedPrincipal>()` which calls `sessions["auth-session"]` → the backend resolves the cookie → `authenticationService.resolve(session)` → returns `null` for expired sessions.
- Ktor's `Authentication` plugin intercepts the request and returns 401, but the `respondBytesWriter` block in `CityAgent.kt:483` **never executes** because Ktor short-circuits the pipeline.
- The frontend's `AuthContext` polls `/auth/me` every 30 seconds. After a poll detects logout, the UI updates. But this is a **delay** — in that window, the UI still shows "Logged in as admin".

**The gap:** The UI only updates on the next poll (up to 30 seconds) or when a 401 is received. Between logout and the next poll, the system is in an inconsistent state.

### Issue 2: No server-side auth logging

**How it works:**
- `CityAgent.kt:466` — Route-level auth check (`call.requirePermission`) — silently fails (Ktor returns 401). No application log.
- `CityAgent.kt:379-383` — Tool-level auth check (`checkAuthorization`) — throws `ToolErrorVisibleToLlm` if `executionContexts[conversationId]` is null. This exception is caught by LangChain4j's error handler and returned to the LLM as a tool result.
- `CityAgent.kt:544` — The SSE handler's `catch` block only logs `WriteTimeoutException` and `CancellationException` as WARN. All other errors logged as ERROR.

**What happens:**
- When auth fails at the route level (line 466), Ktor short-circuits → no log entry in the application logger. The server log shows nothing.
- When auth fails at the tool level (line 379), the exception propagates through LangChain4j → error handler sends message to LLM → LLM tells user "I can't add a city while you're logged out." Still no log entry in the application logger.
- The only log entries are from `CityAgent.kt:489` ("Starting LLM chat request") and `CityAgent.kt:532` ("LLM chat completed"). These don't differentiate between successful and failed auth.

**The gap:** There is **zero visibility** in the server logs when authentication fails. An operator cannot tell from logs alone whether a request was authorized or rejected.

### Issue 3: No recovery after logout

**How it works:**
- Frontend: `App.tsx:233-236` — `confirmPendingAction` re-executes after login. `App.tsx:245-251` — login callback re-sends agent message.
- Frontend: `demoApi.ts:35` — `apiRequest` dispatches `app-auth-required` event on 401.
- Frontend: `AuthContext.tsx:40-47` — Listens for `app-auth-required` → sets `user = null`, opens login dialog.

**What happens:**
- If the agent returns "I can't add a city while you're logged out" via the LLM, the user sees this in the chat UI. But the frontend has no mechanism to know this was an auth rejection — it was a successful 200 SSE response with an assistant message.
- The user cannot "re-trigger" the action because the frontend's `pendingAction` mechanism only triggers on 401 from `apiRequest`, not on auth errors returned through the LLM.
- The session ID (`conversationId`) is stored in `sessionStorage` and persists across login/logout cycles. After re-authentication, the old conversation context is still associated with the old (no longer valid) execution context.

**The gap:** The agent's auth rejection is invisible to the frontend's recovery mechanism. The user has no clear path to resume the conversation.

### Issue 4: The 405 Method Not Allowed

The frontend's `POST /demo/cities` returns 405. This happens when:
- The route `/demo/cities` POST exists but is inside `authenticate("auth-session")` block.
- An unauthenticated POST to `/demo/cities` — Ktor returns 401 for unauthenticated, not 405.
- The 405 likely indicates the route **exists** but the method is not allowed for the specific path/HTTP method combination, suggesting a routing conflict (e.g., a GET-only route is matching the POST).

**Root cause:** The `cityReadRoutes` in `Demo.kt:402-413` defines `get("/demo/cities")` outside the authentication block. If an unauthenticated POST hits `/demo/cities`, Ktor might resolve it to the GET route and return 405 Method Not Allowed instead of 401.

## Design

### Principle: Visibility First

Every auth-relevant operation must produce a log entry. Every auth state change must be visible to the UI. Every auth failure must provide a recovery path.

### Changes Required

#### 1. Backend: Log all auth check results (Visibility)

**File: `CityAgent.kt` — Route handler (line ~465-480)**

Add explicit logging at the SSE route handler start:
```kotlin
post("/demo/agent/chat/stream") {
    val user = call.currentAuthenticatedUser()
    if (user == null) {
        log.warn("Agent chat rejected: unauthenticated request for conversation {} from IP {}", 
                 request.conversationId, call.request.local.remoteHost)
        call.respond(HttpStatusCode.Unauthorized, "Please log in to use the city assistant.")
        return@post
    }
    // ... existing code, with logging
}
```

**File: `CityAgent.kt` — Tool authorization (line ~379-383)**

Log authorization failures in `checkAuthorization`:
```kotlin
private fun checkAuthorization(conversationId: String, permission: Permission) {
    val user = executionContexts[conversationId]
        ?: run {
            log.warn("Agent tool '{}' rejected: no execution context for conversation {}", 
                     "toolName", conversationId)
            throw ToolErrorVisibleToLlm.from("Please log in before accessing city assistant tools.")
        }
    if (!AuthorizationService.hasPermission(user, permission)) {
        log.warn("Agent tool '{}' rejected: user {} lacks permission {}", 
                 "toolName", user.username, permission.name)
        throw ToolErrorVisibleToLlm.from("You are not allowed to perform this operation.")
    }
}
```

#### 2. Backend: Validate execution context matches route auth (Correctness)

**File: `CityAgent.kt` — SSE route handler**

Ensure the user bound in the execution context matches the route-level authenticated user. This catches the case where a conversation ID from a previous session is reused after logout:

```kotlin
val user = call.currentAuthenticatedUser() ?: run {
    log.warn("Agent chat rejected: unauthenticated request for conversation {}", request.conversationId)
    call.respond(HttpStatusCode.Unauthorized, "Please log in to use the city assistant.")
    return@post
}
val conversationId = agentConversationKey(user, request.conversationId)

conversation.lock.withLock {
    // Verify execution context belongs to this user (not stale from another user's session)
    val existingUser = executionContexts[conversationId]
    if (existingUser != null && existingUser.id != user.id) {
        log.warn("Agent chat rejected: stale execution context for conversation {} belongs to user {} but request is from user {}", 
                 request.conversationId, existingUser.id, user.id)
        tools.bindExecutionContext(conversationId, user).close() // cleanup
        call.respond(HttpStatusCode.Forbidden, "Your session changed. Please restart the assistant conversation.")
        return@post
    }
    // ... bind execution context and proceed
}
```

#### 3. Frontend: Detect logout proactively via SSE error

**File: `demoApi.ts` — `streamAgentMessage`**

When the SSE stream is interrupted (client disconnect, auth rejection), detect it and dispatch the auth event:

```typescript
export async function streamAgentMessage(
  conversationId: string,
  message: string,
  onEvent: (event: AgentStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
    const response = await apiRequest('/demo/agent/chat/stream', ...)
    if (!response.ok) {
        if (response.status === 401) {
            window.dispatchEvent(new Event('app-auth-required'))
            throw new ApiError('Please log in to use the city assistant.', response.status)
        }
        throw new ApiError(...)
    }
    // ... rest of streaming logic
}
```

#### 4. Frontend: Poll auth status more frequently

**File: `AuthContext.tsx` — line 50-58**

Change polling interval from 30 seconds to 10 seconds:
```typescript
const interval = window.setInterval(() => {
    void fetchAuthenticationStatus()
        .then((response) => { if (!response.authenticated) window.dispatchEvent(new Event('app-auth-required')) })
        .catch(() => window.dispatchEvent(new Event('app-auth-required')))
}, 10_000) // was 30_000
```

#### 5. Backend: Fix 405 on POST /demo/cities

**File: `Demo.kt` — line 406-408**

Move `cityReadRoutes` GET `/demo/cities` outside the main routing, or ensure POST routes take precedence. The 405 likely occurs because Ktor resolves the GET route first and rejects the POST method.

Alternatively, add explicit error handling:
```kotlin
get("/demo/cities") { ... } // only GET
// POST is only under authenticate("auth-session") block
```

Ensure the POST route at line 342 is correctly matched.

#### 6. Frontend: Show login prompt when agent reports auth error

**File: `App.tsx` — line 183-214**

When the assistant returns a message containing "log in" or "logged out", detect it and offer the user a "Log in now" button:

```typescript
// In the SSE event handler, check if the error message indicates auth failure
if (event.type === 'done' || event.type === 'error') {
    const finalContent = responseText.toString()
    if (finalContent.toLowerCase().includes('log in') || 
        finalContent.toLowerCase().includes('logged out') ||
        finalContent.toLowerCase().includes('authentication')) {
        // Auto-open login dialog
        auth.openLogin('The assistant needs you to be logged in. Please log in to continue.')
    }
}
```

#### 7. Backend: Auto-clear stale execution contexts on auth change

**File: `Authentication.kt` — after logout**

When a user logs out, clear any execution contexts associated with that user:

```kotlin
// In the /auth/logout handler:
authenticationService.logout(call.sessions.get<AuthSession>())
call.sessions.clear<AuthSession>()
// Clear execution contexts for this user across all conversations
CityAgentTools.clearExecutionContextsForUser(user)
```

This requires exposing a `clearExecutionContextsForUser` method on `CityAgentTools` that `ConcurrentHashMap` entries where the value matches the logging-out user.

### Implementation Plan

1. **Phase 1 — Visibility**: Add logging in `CityAgent.kt` route handler and `checkAuthorization`. Fix 405 issue in `Demo.kt`.
2. **Phase 2 — Correctness**: Add execution context validation in SSE route handler. Clear stale contexts on logout.
3. **Phase 3 — Recovery**: Frontend polling interval reduction. Auto-open login on agent auth rejection.
4. **Phase 4 — Verification**: Test login → operate → logout → retry flow. Verify logs show auth decisions. Verify UI updates on logout.
