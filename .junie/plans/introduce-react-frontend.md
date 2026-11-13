---
sessionId: session-261112-220239-1bql
---

# Requirements

### Overview & Goals
Introduce a lightweight React + TypeScript frontend alongside the existing single-module Ktor application, without rewriting backend business logic or replacing current integrations.

### Scope
**In scope**
- Add a dedicated `frontend/` Vite application using React and TypeScript.
- Establish `/app/` as the React-owned route space while preserving existing Ktor/Thymeleaf routes.
- Add typed client service boundaries for the existing REST endpoints and WebSocket endpoint.
- Configure development proxying and production asset packaging/serving through the existing application where practical.
- Document frontend development, build, and deployment workflows.
- Add a LangChain4j-backed streaming chat endpoint for the configured OpenAI-compatible `openai/gpt-oss-20b` model.
- Expose guarded city create, update, and delete tools that resolve existing cities through Elasticsearch and reuse the database/index/WebSocket change pipeline.
- Retain bounded conversation memory and support asking for a missing population before creating a city.

**Out of scope**
- Introducing Spring Boot, Spring Security, GraphQL, Apollo, GraphQL Code Generator, or a second backend server; none exists in this repository.
- Rewriting existing `Demo.kt`, `Rabbitmq.kt`, `Websocket.kt`, PostgreSQL setup, or Thymeleaf functionality beyond the minimum integration needed to serve the frontend.
- Migrating all current pages to React or adding authentication infrastructure that is not currently present.

### Functional Requirements
- A developer can run the frontend independently with Vite and proxy `/demo/**` and `/demo/ws` traffic to Ktor.
- The production frontend is built into static assets and served under `/app/` by Ktor without intercepting existing routes or WebSocket upgrades.
- React can display/use existing city REST operations and RabbitMQ message updates through the existing WebSocket protocol.
- Browser refresh and direct navigation for React routes under `/app/` resolve to the React entry point.
- Existing backend tests and existing Thymeleaf/server routes remain functional.

# Technical Design

### Current Implementation
The project is a single Gradle Kotlin/JVM module using Ktor Netty (`build.gradle.kts`), with application modules registered in `src/main/resources/application.yaml`. `Demo.kt` currently embeds the primary demo HTML and calls `/demo/cities`, `/demo/rabbitmq/publish`, and `/demo/ws`. `Websocket.kt` broadcasts serialized `WsMessage` values from `ConsumerState.messages`; `Rabbitmq.kt` provides the RabbitMQ consumer and publish routes. There is no GraphQL endpoint, Spring Boot layer, Spring Security configuration, existing frontend tooling, or frontend module.

### Key Decisions
- **Ktor-native integration:** keep Ktor as the application platform and add only the React presentation layer; do not introduce a framework migration that the repository does not support.
- **Separated ownership:** React owns `/app/**`; existing Ktor/Thymeleaf behavior remains under its current paths. The SPA fallback must be constrained to `/app` paths so API and WebSocket requests are never treated as frontend routes.
- **Same-origin production:** package the Vite output into Ktor resources and serve it through a small Ktor route/static-resource integration. During development, Vite proxies backend requests to the Ktor server.
- **Existing protocols:** use native `fetch` for current REST endpoints and the browser `WebSocket` API for `/demo/ws`; defer Apollo/GraphQL until a real GraphQL backend is added.

### Proposed Changes
- Create `frontend/` with strict TypeScript, Vite, React, a minimal router, application entry/providers, domain-oriented components, REST services, WebSocket hook, and styles.
- Add an initial `/app/` screen that replaces only the presentation of existing demo capabilities: city listing/actions and live RabbitMQ message output. Keep backend routes unchanged.
- Configure Vite base path and development proxy for `/demo`; use a reproducible frontend build command.
- Add Gradle integration only if it can remain simple and deterministic: install frontend dependencies, run the frontend build, and copy output into Ktor resources without committing generated artifacts.
- Add Ktor static serving and narrowly scoped SPA fallback for `/app` and `/app/**`, preserving `/demo/**`, `/demo/ws`, and other existing routes.
- Update `README.md` with backend/frontend startup, build, WebSocket behavior, and packaging instructions.

### Data Models / Contracts
- Reuse the existing REST response shape for city records (`id`, `name`, `population`).
- Reuse `WsMessage` JSON (`body`) emitted by `Websocket.kt`.
- Keep publish behavior on the existing `POST /demo/rabbitmq/publish` route; the frontend service should treat the response/status as the operation result.
- Do not add GraphQL contracts or generated types until a GraphQL schema and endpoint exist.
- Stream model output to the browser as server-sent events from a POST endpoint; keep `conversationId` in the request and bound the corresponding chat memory.
- City tools must identify update/delete targets by an unambiguous exact name match from Elasticsearch, never require the model to invent database IDs, and route mutations through the existing persistence and `city-changed` notification path.
- A city create tool with no population must retain pending context and ask the user for that required value rather than inventing a default.

### Risks
- The current `Demo.kt` embeds HTML and may overlap conceptually with the new `/app` page; the initial implementation should leave it intact and avoid duplicate route ownership.
- Static asset packaging must not make tests require a prebuilt frontend; test configuration should continue to run without external Node tooling where possible.
- WebSocket reconnect and cleanup must be handled in the React hook so navigation does not leak connections.

# Testing

### Validation Approach
- Run the existing `./gradlew test` and verify the backend still compiles and tests pass.
- Run the frontend package's install/build/type-check commands and confirm Vite emits production assets.
- Start Ktor with the packaged frontend and verify `/app/` and a nested `/app/...` route load the SPA entry point.
- Verify `/demo/cities` and `/demo/rabbitmq/publish` remain reachable and are not captured by SPA fallback.
- Open the React page, confirm city data loads, publish a message, and confirm WebSocket-delivered `WsMessage.body` values appear.
- Verify WebSocket reconnect behavior after a disconnect and confirm no duplicate connections are created during route changes.
- Verify chat token streaming, memory across turns, missing-population follow-up, unique Elasticsearch name resolution, and city-change notification after tool-driven mutations.

# Delivery Steps

### ✓ Step 1: Establish the React frontend skeleton
A strict TypeScript Vite React application exists under `frontend/` with a minimal `/app/` entry point.

- Add the frontend package metadata, Vite configuration, TypeScript configuration, and source structure.
- Configure the Vite base path for `/app/` and development proxying to the Ktor server.
- Add the initial app shell, routing boundary, and lightweight styling without introducing GraphQL or unsupported backend dependencies.

### ✓ Step 2: Connect React to existing Ktor APIs
The React application displays current city data and RabbitMQ events using the repository’s existing contracts.

- Add a REST service using native `fetch` for `/demo/cities` and `/demo/rabbitmq/publish`.
- Add a WebSocket hook for `/demo/ws` that parses `WsMessage` payloads, reconnects safely, and cleans up on unmount.
- Implement the initial `/app/` screen and components for city operations, publish status, and live messages.

### ✓ Step 3: Serve the SPA from Ktor without disrupting existing routes
A production frontend build is served under `/app/` while existing Ktor and WebSocket routes retain ownership of their paths.

- Add the narrowly scoped static-resource and SPA fallback handling in the Ktor routing/configuration layer.
- Integrate the frontend build into the Gradle packaging workflow, or document the explicit asset-copy step if build integration would make tests or local builds brittle.
- Verify that `/demo/**`, `/demo/ws`, and existing server-rendered pages are excluded from SPA fallback.

### ✓ Step 4: Document and validate the incremental migration
The repository documents and verifies the new frontend workflow without claiming unavailable GraphQL or Spring integrations.

- Update `README.md` with independent development, frontend build, same-origin production, and WebSocket guidance.
- Add or update tests for route preservation and frontend packaging where the existing test setup can support them.
- Run Gradle tests plus frontend type-check/build validation and verify the primary browser flows.

### ✓ Step 5: Add the LangChain4j city assistant backend
The Ktor backend exposes a conversation-aware streaming LLM endpoint and safe tools for existing city operations.

- Configure the OpenAI-compatible endpoint and model, and add LangChain4j streaming dependencies.
- Stream partial assistant responses to the client and retain bounded message history per conversation.
- Implement create/update/delete tools; resolve update/delete targets through Elasticsearch by exact city name and reuse the existing persistence and WebSocket notification path.
- Ask for missing create population and complete the pending operation when the user replies; test missing input, follow-up, and safe city resolution.

### ✓ Step 6: Integrate and validate the assistant in React
The `/app/` page presents the streamed conversation and the repository documents and verifies the new assistant workflow.

- Add a responsive chat area and consume the backend event stream incrementally.
- Preserve existing city controls and RabbitMQ message display.
- Document LLM configuration, conversation behavior, and the stream endpoint.
- Run backend and frontend verification.

### * Step 5: Add the LangChain4j streaming agent and city tools
The Ktor backend streams model responses and supports bounded conversation context plus safe city create/update/delete tools.

- Add LangChain4j OpenAI-compatible streaming dependencies and configuration for the provided endpoint/model, keeping credentials configurable and out of source control.
- Add the SSE chat route, request/response types, bounded per-conversation memory, and robust error/completion signaling.
- Add city tools that use Elasticsearch name lookup, reject missing/ambiguous targets, validate required create population, and invoke the existing persistence/index/WebSocket pipeline.
- Add backend tests for conversation input validation, city resolution/missing fields, and unchanged existing routes as feasible without a live LLM.

###   Step 6: Add the React conversation interface
The React page streams assistant output into a conversation panel, positioned responsively alongside the existing demo tools on large screens.

- Add a chat service that POSTs messages and incrementally parses SSE response events.
- Add conversation state and input UI, preserving conversation ID across turns and rendering partial tokens as they arrive.
- Place the conversation beside the city/search/RabbitMQ area on large screens and below it on smaller screens.
- Keep city list refresh driven by the existing WebSocket `city-changed` event after agent mutations.

###   Step 7: Document and validate the agent integration
The README documents model endpoint configuration and the supported streaming/tool workflow, and backend/frontend validation passes.

- Document the model base URL/model configuration, required runtime setup, streaming protocol, conversation-memory limits, and tool behavior.
- Run Gradle tests and frontend type-check/build; verify stream protocol and UI behavior with available local tooling, clearly noting any external LLM/Elasticsearch/RabbitMQ constraints.
