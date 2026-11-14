# SSE Tool-Call Parser Error

## Problem

When the LLM uses a tool during streaming (e.g., `operationHistory`, `listCities`), the streaming endpoint fails with:

```
dev.langchain4j.exception.InternalServerException: Chat completion failed: unexpected tokens remaining in message header: ["to=functions.operationHistory"]
```

The error occurs inside `OpenAiStreamingChatModel.doChat()` in langchain4j's SSE response parser.

## Root Cause

This is a **compatibility mismatch** between VLLM's SSE stream format and langchain4j's SSE parser.

### What's happening

1. The OpenAI-compatible API spec defines SSE events as a stream of text blobs, where each event starts with `data: ` and ends with a blank line.

2. langchain4j's SSE parser assumes a strict OpenAI format and uses a hand-written tokeniser to parse SSE headers.

3. VLLM, when emitting tool-call events in the stream, includes additional metadata fields (such as `to=functions.operationHistory`) that langchain4j's parser does not expect in that position.

4. When the parser encounters `to=functions.operationHistory` in an unexpected location in the SSE header, it treats it as "unexpected tokens" and throws `InternalServerException`.

### Why it only happens with tool calls

The model's regular text completion (no tools invoked) produces simple `delta` SSE events that langchain4j can parse without issue. Tool-call events in VLLM's output include extra fields or a different structure that the parser chokes on.

## Why It's Invisible to Monitoring

The error propagates as an `InternalServerException` with a cryptic message. In `CityAgent.kt`, the `onError` callback fires with this exception, and the user sees:

```
The assistant is temporarily unavailable: Chat completion failed: unexpected tokens remaining in message header: ["to=functions.operationHistory"]
```

The actual cause (VLLM/SSE format mismatch) is buried in the stack trace. The VLLM server itself logs nothing — the error occurs on the **client side** (langchain4j) during stream parsing.

## Affected Components

| Component | Version | Role |
|-----------|---------|------|
| langchain4j | 1.23.0 | SSE parser in `langchain4j-open-ai` module |
| VLLM | — | OpenAI-compatible API server (produces non-standard SSE for tool calls) |

## Possible Solutions

### Option A: Upgrade langchain4j

Check if a newer version of langchain4j has improved SSE parsing for VLLM-compatible servers. langchain4j is actively maintained and this parsing issue may have been fixed.

### Option B: Switch HTTP client transport

langchain4j's OpenAI streaming model can be configured with a different HTTP client (e.g., Apache HttpClient or OKHttp instead of the default JDK HttpClient). Some HTTP client backends have more lenient SSE parsing:

```kotlin
OpenAiStreamingChatModel.builder()
    .baseUrl(...)
    .apiKey(...)
    .modelName(...)
    .timeout(Duration.ofSeconds(120))
    .httpClient(OKHttpHttpClient.newBuilder().build()) // alternative
    .build()
```

### Option C: Parse and mask the error

In `CityAgent.kt`, detect this specific error pattern and surface a more actionable message to the user (or retry with a different approach):

```kotlin
.onError { error ->
    val isSseToolCallError = error.message?.contains("unexpected tokens remaining in message header") ?: false
    if (isSseToolCallError) {
        // Retry non-streaming, or fall back to a different model
        log.warn("SSE parser error during tool call — falling back to non-streaming for conversation {}", request.conversationId)
    }
}
```

### Option D: Non-streaming fallback

When tool calls are detected in the system prompt (as they are in this project), consider switching from streaming to non-streaming mode. Non-streaming responses are single JSON blobs and don't go through SSE parsing, completely avoiding this issue:

```kotlin
// Instead of TokenStream streaming:
assistant.chat(conversationId, request.message)
    .onPartialResponse { ... }
    .start()

// Use non-streaming:
val response = assistant.chat(conversationId, request.message)
// Then stream the response text manually via SSE
```

## Recommended Action

Given that:
- This project **always uses tools** (city CRUD, operation history, etc.)
- The error is a **client-side parsing issue** (not a server problem)
- The fix requires either upgrading langchain4j or changing the streaming approach

**Priority**: Medium. The error is reproducible whenever the LLM invokes a tool. It blocks all tool-using conversations.

**Recommended approach**: Try upgrading langchain4j first (simplest fix). If no newer version resolves it, implement a non-streaming fallback (Option D) or switch HTTP client backend (Option B).

## Related Errors

This error is distinct from the previously investigated issues:

| Error | Cause | Status |
|-------|-------|--------|
| `HTTP/1.1 header parser received no bytes` | VLLM closed connection before response headers | Partially mitigated with retry |
| `unexpected tokens remaining in message header` | SSE parser incompatibility with VLLM's tool-call events | **Not yet fixed** |
| Empty response completion | Model overloaded / context full / silent timeout | Mitigated with observability |

## Timeline

| Date | Event |
|------|-------|
| 2026-11-13 15:11 | HTTP/1.1 EOF errors observed — transient connection resets |
| 2026-11-13 15:47 | SSE tool-call parser error observed with `to=functions.operationHistory` |
