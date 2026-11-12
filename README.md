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

Vite serves the frontend under `/app/` and proxies `/demo/**`, including `/demo/ws`, to Ktor on port `8080`. The frontend uses native `fetch` for city and publish operations and the browser WebSocket API for live RabbitMQ messages; there is no GraphQL endpoint in this Ktor application.

### Elasticsearch city search

`docker compose up -d` starts Elasticsearch on port `9200` alongside PostgreSQL and RabbitMQ. Ktor creates the `cities` index and reindexes database cities at startup; newly created cities are indexed immediately. `GET /demo/cities/search?name=lon` queries Elasticsearch, and the React search field sends a request after each keystroke with a 20 ms debounce.

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
