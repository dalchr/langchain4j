# ktor-app

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

* [Ktor Documentation](https://ktor.io/docs/home.html)
* [Ktor GitHub page](https://github.com/ktorio/ktor)
* [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).

## Features

Here's a list of features included in this project:

| Name                                                                                  | Description                                                                        |
|---------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| [Authentication](https://start.ktor.io/p/io.ktor/server-auth)                         | Provides extension point for handling the Authorization header                     |
| [Thymeleaf](https://start.ktor.io/p/io.ktor/server-thymeleaf)                         | Serves HTML content, templated using Thymeleaf                                     |
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation)     | Provides automatic content conversion according to Content-Type and Accept headers |
| [kotlinx.serialization](https://start.ktor.io/p/io.ktor/server-kotlinx-serialization) | Handles JSON serialization using kotlinx.serialization library                     |
| [PostgreSQL](https://start.ktor.io/p/org.jetbrains/server-postgres)                   | Adds Postgres database support                                                     |
| [RabbitMQ](https://start.ktor.io/p/io.github.damirdenis-tudor/server-rabbitmq)        | Adds RabbitMQ support to your application                                          |

## Building & Running

To build or run the project, use one of the following tasks:

| Task              | Description       |
|-------------------|-------------------|
| `./gradlew test`  | Run the tests     |
| `./gradlew build` | Build the project |
| `./gradlew run`   | Run the server    |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## React frontend

The React + TypeScript frontend lives in `frontend/` and owns the `/app/` URL space. Existing Ktor demo and Thymeleaf routes remain unchanged.

### Development

Start the backend with `./gradlew run`, then start Vite in a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Vite serves the frontend under `/app/` and proxies `/auth/**` and `/demo/**`, including `/demo/ws`, to Ktor on port `8080`. The frontend uses native `fetch` for city and publish operations and the browser WebSocket API for live RabbitMQ messages; there is no GraphQL endpoint in this Ktor application.

### Authentication and authorization

City list and search reads (`GET /demo/cities` and `GET /demo/cities/search`) are public. City create, update, and delete operations, the assistant endpoints, publish action, and `/demo/ws` require a server-side authenticated session. In IntelliJ, use the shared **Ktor Development** run configuration; it sets the local-only credentials. From a terminal, start Ktor with:

```bash
APP_ENV=development \
DEV_AUTH_ENABLED=true \
DEV_AUTH_USERNAME=chris \
DEV_AUTH_PASSWORD=chris \
./gradlew run
```

These are development-only credentials; the password is BCrypt-hashed at startup and is not stored in the repository or database. Local authentication is rejected unless `APP_ENV=development`; setting `APP_ENV=production` while enabling it prevents startup. Without local auth configured, login responds with `503 Service Unavailable`.

The API provides `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`, and `GET /auth/status`. The UI uses `GET /auth/csrf` to obtain a double-submit CSRF token and includes it in a request header for state-changing calls. Session cookies are HttpOnly, SameSite=Lax, and expire after eight hours; production mode marks cookies Secure and requires `SESSION_ENCRYPTION_KEY` (16, 24, or 32 UTF-8 bytes) and `SESSION_SIGNING_KEY` (at least 32 UTF-8 bytes). Supply high-entropy values through a secret manager and terminate HTTPS at the application or a trusted TLS-terminating proxy. The server also sets basic content-type, framing, referrer, and permissions security headers. `/demo/ws` uses the same session cookie; connections are closed after logout or expiry (and revalidated when messages are broadcast).

After login, a pending CRUD, assistant, publish, or restart action is shown for review and requires a separate confirmation; it is never replayed automatically. Five failed login attempts block that client address for 15 minutes; the in-memory limiter is bounded. Sessions and local accounts currently live in process memory, so sessions are invalidated on restart and this implementation is not suitable for a multi-instance deployment without shared session storage. The authentication service boundary is designed to be replaceable, but Keycloak/OIDC integration is not implemented yet.

### LLM city assistant

The React page includes a conversation panel backed by LangChain4j and the OpenAI-compatible streaming endpoint configured in `src/main/resources/application.yaml` (`llm.base-url` and `llm.model-name`). The defaults use `http://finndet-host02:8000/v1/chat/completions` with model `openai/gpt-oss-20b`. Set `LLM_API_KEY` in the Ktor process environment if the configured model server requires an API key; otherwise the backend uses a placeholder key.

The browser sends `POST /demo/agent/chat/stream` with `{ "conversationId": "…", "message": "…" }`. The response is Server-Sent Events with JSON `token`, `done`, and `error` events; token content is rendered as it arrives. The conversation ID is kept in browser session storage, and the backend retains a bounded message window per conversation. A separate bounded activity timeline records user messages, completed assistant replies, and successfully queued city operations in order; the assistant consults it for questions about earlier actions. Queued operations indicate RabbitMQ accepted the command, not that asynchronous database persistence succeeded. The **Restart** button calls `POST /demo/agent/conversation/reset` with the conversation ID; the server clears chat memory, activity history, and any pending create operation before the UI clears the visible transcript.

For city-related questions, the assistant uses its city-context tools rather than relying on model knowledge: general questions list registered city names and populations, and a specific city is looked up by name to report whether it exists and its current indexed population. Create requests without a population ask for that value before writing. Update and delete requests resolve an exact, unique city name through Elasticsearch, then use the existing RabbitMQ-backed persistence, reindexing, and WebSocket city refresh flow. Elasticsearch must be available for city list and lookup operations.

### Elasticsearch city search

`docker compose up -d` starts Elasticsearch on port `9200` alongside PostgreSQL and RabbitMQ. Ktor creates the `cities` index and reindexes database cities at startup; newly created cities are indexed immediately. `GET /demo/cities/search?name=lon` queries Elasticsearch, and the React search field sends a request after each keystroke with a 20 ms debounce.

### Domain Operation Tool Design

The agent tooling follows a "LLM = intent, Application = execution" pattern: complex multi-entity operations are delegated to deterministic service methods (e.g., `updateAllCities(delta)` applies a population change to every city atomically, without the model constructing individual update payloads). This approach eliminates the fragility of having the LLM manage entity state across conversation turns. See `docs/solutions/domain-operation-design.md` for the full design principles, tool taxonomy, anti-patterns, and testing checklist.

### Production assets

Build the frontend and copy its output into Ktor resources before creating the application artifact:

```bash
cd frontend
npm ci
npm run build
cd ..
mkdir -p src/main/resources/frontend
cp -R frontend/dist/. src/main/resources/frontend/
./gradlew clean build
```

The copied assets are served from `/app/` by Ktor, including SPA fallback for nested `/app/...` routes. Keep generated assets out of version control unless the deployment process explicitly requires them. The fallback is limited to `/app`, so `/demo/**`, `/demo/ws`, Thymeleaf pages, and other backend routes retain ownership of their paths.
