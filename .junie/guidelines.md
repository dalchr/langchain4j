# Project Guidelines

## Project Overview
This is a Ktor application built with Kotlin using Gradle (Kotlin DSL). It runs on the Netty server and integrates with PostgreSQL, RabbitMQ, H2 (for testing), and Thymeleaf for server-side templating. The project is single-module with a flat directory structure (`src/main/kotlin`, `src/test/kotlin`).

### Top-level Structure
- README.md — High-level documentation, feature list, and build/run instructions.
- [build.gradle.kts](../build.gradle.kts) — Gradle build configuration (Kotlin DSL) with Ktor, Kotlin, and serialization plugins.
- [settings.gradle.kts](../settings.gradle.kts) — Gradle settings (project name and version catalog management).
- [gradle.properties](../gradle.properties) — Gradle properties (toolchain version, etc.).
- [gradlew](../gradlew) / [gradlew.bat](../gradlew.bat) — Gradle wrapper scripts.
- `src/main/kotlin` — Kotlin source files (Ktor application entry point and feature modules).
- `src/main/resources` — Configuration files (`application.yaml`, `logback.xml`, `index.html`).
- `src/test/kotlin` — Test source files.

### Technology Stack
- **Ktor** (Netty engine) — Server framework
- **Kotlin** (JVM 21) — Primary language
- **kotlinx.serialization** — JSON serialization
- **PostgreSQL** — Production database
- **H2** — In-memory database for tests
- **RabbitMQ** — Message broker
- **Thymeleaf** — Server-side HTML templating
- **YAML config** — Application configuration via `application.yaml`

### Main Class
`io.ktor.server.netty.EngineMain` (defined in `build.gradle.kts`)

## How Junie Should Work in This Project
- Keep changes minimal and targeted to the described issue.
- Prefer editing existing files over creating new ones unless the issue explicitly requires it.
- **Flat structure**: Source files are at most ~5 levels deep (e.g., `src/main/kotlin/package/Class.kt`). Do not use excessively deep glob patterns.
- Use Gradle for all build and test operations:
  - `./gradlew test` — Run tests
  - `./gradlew build` — Build the project
  - `./gradlew run` — Run the server
- For Kotlin code changes, run `./gradlew test` to verify the project still compiles and tests pass.
- If a module has tests, run them via Gradle (this is a single-module project). If unsure, ask for guidance.

## Parallel Execution

Prefer parallel tool calls (file reads, searches, analysis) over sequential lookups to save time and tokens.

## Code Style & Conventions
- Follow existing patterns in the module you edit.
- Keep public API changes backward-compatible unless the issue states otherwise.
- Add brief inline comments only where intent is non-obvious.
- Kotlin conventions: idiomatic style, `const` for compile-time constants, `val`/`var` as appropriate, use of Ktor DSL patterns for route/feature registration.

