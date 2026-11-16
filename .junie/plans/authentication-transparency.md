# Implementation Plan: Authentication Transparency

## Problem Overview

The city assistant's authentication system operates as a black box: when a user logs out, the UI still shows "Logged in as admin", server logs reveal nothing about auth failures, and the user has no way to recover from an agent auth rejection. Additionally, `POST /demo/cities` returns 405 instead of 401 for unauthenticated requests.

## Root Causes Identified

1. **Silent auth failures**: Route-level Ktor auth (CityAgent.kt:466) short-circuits without logging. Tool-level auth (CityAgent.kt:379) throws exceptions that don't get logged by the application logger.
2. **No execution context lifecycle**: Stale `executionContexts` entries persist after logout. No cleanup mechanism.
3. **Routing conflict**: `cityReadRoutes` defines GET `/demo/cities` outside auth block; unauthenticated POST may resolve to GET route → 405.
4. **Frontend blind to agent auth errors**: Agent auth rejection returns 200 SSE with an error message — frontend never receives 401, never opens login dialog.
5. **Slow auth polling**: 30-second interval delays UI sync after logout.

## Implementation Steps

### Step 1: Add auth logging to CityAgent route handler and checkAuthorization

**File: `src/main/kotlin/CityAgent.kt`**

#### 1a. Route handler — explicit auth check with logging (replace `call.requirePermission`)

Replace the `call.requirePermission(Permission.CHAT_ACCESS)` at line 465-470 with explicit user resolution and logging:

```kotlin
post("/demo/agent/chat/stream") {
    if (!call.requireCsrfToken()) return@post
    val request = runCatching { call.receive<AgentChatRequest>() }.getOrElse {
        call.respond(HttpStatusCode.BadRequest, "Invalid chat request")
        return@post
    }
    if (request.conversationId.isBlank() || request.conversationId.length > 128 ||
        request.message.isBlank() || request.message.length > 4000
    ) {
        call.respond(HttpStatusCode.BadRequest, "A conversation ID and a message of at most 4000 characters are required")
        return@post
    }

    val user = call.currentAuthenticatedUser()
    if (user == null) {
        log.warn("Agent chat rejected: unauthenticated request for conversation {} from IP {}",
                 request.conversationId, call.request.local.remoteHost)
        call.respond(HttpStatusCode.Unauthorized, "Please log in to use the city assistant.")
        return@post
    }
    log.info("Agent chat accepted: user {} for conversation {}", user.username, request.conversationId)
    // ... rest of existing code
}
```

#### 1b. checkAuthorization — add logging before throwing

```kotlin
private fun checkAuthorization(conversationId: String, permission: Permission) {
    val user = executionContexts[conversationId]
        ?: run {
            log.warn("Agent tool execution rejected: no execution context for conversation {} (user likely logged out)", conversationId)
            throw ToolErrorVisibleToLlm.from("Please log in before accessing city assistant tools.")
        }
    if (!AuthorizationService.hasPermission(user, permission)) {
        log.warn("Agent tool execution rejected: user {} lacks permission {}", user.username, permission.name)
        throw ToolErrorVisibleToLlm.from("You are not allowed to perform this operation.")
    }
}
```

### Step 2: Add execution context validation and stale context cleanup

**File: `src/main/kotlin/CityAgent.kt`**

In the route handler, after resolving `user` and before binding execution context, check if an existing context exists for a different user:

```kotlin
// After: val user = call.currentAuthenticatedUser()
// After: val conversationId = agentConversationKey(user, request.conversationId)

// Validate no stale context from another user
val existingUser = tools.executionContexts[conversationId]
if (existingUser != null && existingUser.id != user.id) {
    log.warn("Agent chat rejected: stale execution context for conversation {} belongs to user {} but request is from user {}",
             request.conversationId, existingUser.id, user.id)
    call.respond(HttpStatusCode.Forbidden, "Your session changed. Please restart the assistant conversation.")
    return@post
}
```

Also add a `clearExecutionContextsForUser` method to `CityAgentTools` and call it from the logout handler.

### Step 3: Fix 405 on POST /demo/cities

**File: `src/main/kotlin/Demo.kt`**

The `cityReadRoutes` at line 402-413 defines GET `/demo/cities` in the same routing block but outside the `authenticate("auth-session")` block. Ktor may be resolving POST → GET and returning 405.

Move `cityReadRoutes` call to be before the `authenticate` block, or restructure so POST routes are registered first.

Actually, looking more carefully — `cityReadRoutes` is called at line 114-126 (inside the `configureDemo` routing block but outside `authenticate`), and the POST/PUT/DELETE routes are at lines 342-374 inside `authenticate("auth-session")`. The issue is that unauthenticated POST to `/demo/cities` will try to match any route, find the GET route, and return 405.

Fix: Make GET `/demo/cities` only accessible for authenticated users, OR add a POST fallback that returns 401 for unauthenticated requests.

The simplest fix: Move the `cityReadRoutes` registration into the `authenticate("auth-session")` block since city operations should require auth anyway.

### Step 4: Frontend — auto-open login on agent auth rejection

**File: `frontend/src/app/App.tsx`**

In the `sendAgentRequest` function (line 171-214), after the streaming completes, check if the final response indicates an auth failure and auto-open the login dialog.

### Step 5: Frontend — reduce auth polling interval

**File: `frontend/src/auth/AuthContext.tsx`**

Change interval from 30s to 10s (line 57).

### Step 6: Verify with tests

Run `./gradlew test` to ensure all existing tests still pass.
