package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import io.ktor.server.thymeleaf.Thymeleaf
import io.ktor.server.thymeleaf.ThymeleafContent
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

fun Application.configureRouting() {
    routing {
        staticResources("/app", "frontend")
        get("/app") { call.respondRedirect("/app/") }
        get("/app/{...}") {
            call.respondResource("frontend/index.html")
        }
        get("/") {
            call.respondText(
                """<!DOCTYPE html>
<html>
<head><title>Ktor App</title>
<style>
  body { font-family: sans-serif; max-width: 600px; margin: 3rem auto; text-align: center; }
  h1 { color: #2b6cb0; }
  .link { display: inline-block; margin: 0.5rem; padding: 0.75rem 1.5rem; background: #3182ce;
          color: white; text-decoration: none; border-radius: 4px; }
  .link:hover { background: #2b6cb0; }
</style>
</head>
<body>
  <h1>Ktor Application</h1>
  <a href="/demo" class="link">🏙️ Demo (PostgreSQL + RabbitMQ)</a>
  <a href="/json/kotlinx-serialization" class="link">📄 JSON</a>
  <a href="/rabbitmq" class="link">🐰 RabbitMQ Test</a>
</body>
</html>""".trimIndent(),
                ContentType.Text.Html
            )
        }
        get("/html-thymeleaf") {
            call.respond(ThymeleafContent("index", mapOf("user" to ThymeleafUser(1, "user1"))))
        }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
    }
}