package com.example

import io.github.damir.denis.tudor.ktor.server.rabbitmq.RabbitMQ
import io.github.damir.denis.tudor.ktor.server.rabbitmq.dsl.*
import io.github.damir.denis.tudor.ktor.server.rabbitmq.rabbitMQ
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.*
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.WriteTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager

/**
 * Netty channel handler that catches WriteTimeoutException and CancellationException
 * before Netty's default handler processes them. Logs them as WARN instead of ERROR
 * with full stack trace, making them traceable and quiet.
 */
@Sharable
private class ApplicationExceptionHandler(
    private val applicationLog: org.slf4j.Logger,
) : ChannelInboundHandlerAdapter() {

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val isWriteTimeout = cause::class.java.name == "io.netty.handler.timeout.WriteTimeoutException"
        val isCancellation = cause is java.util.concurrent.CancellationException

        if (isWriteTimeout || isCancellation) {
            if (isWriteTimeout) {
                applicationLog.warn("Client disconnected during streaming: {}", cause.message)
            } else {
                applicationLog.warn("Client request cancelled: {}", cause.message)
            }
        } else {
            super.exceptionCaught(ctx, cause)
        }
    }
}

fun Application.configureDemo() {
    val app = this

    // Install Netty pipeline handler to catch WriteTimeoutException before it becomes a 502 error
    val nettyEngine = engine as? NettyApplicationEngine
    nettyEngine?.configuration?.channelPipelineConfig = {
        addLast("applicationExceptionHandler", ApplicationExceptionHandler(app.log))
    }

    val isEmbedded = environment.config.propertyOrNull("postgres.embedded")?.getString()
        ?.toBoolean() ?: false
    val dbConnection: Connection = DriverManager.getConnection(
        if (isEmbedded) "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
        else environment.config.property("postgres.url").getString(),
        if (isEmbedded) "root"
        else environment.config.property("postgres.user").getString(),
        if (isEmbedded) ""
        else environment.config.property("postgres.password").getString()
    )
    val databaseUrl = if (isEmbedded) "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
    else environment.config.property("postgres.url").getString()
    val databaseUser = if (isEmbedded) "root" else environment.config.property("postgres.user").getString()
    val databasePassword = if (isEmbedded) "" else environment.config.property("postgres.password").getString()
    CityChangeProcessor.connectionProvider = {
        DriverManager.getConnection(databaseUrl, databaseUser, databasePassword)
    }
    val elasticsearch = ElasticsearchClient(
        environment.config.propertyOrNull("elasticsearch.url")?.getString() ?: "http://localhost:9200"
    )
    CityChangeProcessor.search = elasticsearch
    val readAuthoritativeCities = {
        DriverManager.getConnection(databaseUrl, databaseUser, databasePassword).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, name, population FROM cities ORDER BY id").use { result ->
                    buildList {
                        while (result.next()) add(CityView(result.getInt(1), result.getString(2), result.getInt(3)))
                    }
                }
            }
        }
    }
    val cityAgent = CityAgent(
        application = app,
        baseUrl = environment.config.propertyOrNull("llm.base-url")?.getString()
            ?: "http://finndet-host02:8000/v1/chat/completions",
        modelName = environment.config.propertyOrNull("llm.model-name")?.getString() ?: "openai/gpt-oss-20b",
        apiKey = System.getenv("LLM_API_KEY") ?: "not-needed",
        searchCities = elasticsearch::searchByName,
        listCities = readAuthoritativeCities,
        enqueueChange = { command ->
            runBlocking(Dispatchers.IO) { CityCommandPublisher.publish(command) }
        },
        // authoritativeCities = readAuthoritativeCities,
    )
    runCatching {
        val cities = dbConnection.createStatement().executeQuery("SELECT id, name, population FROM cities").let { rs ->
            buildList { while (rs.next()) add(CityView(rs.getInt(1), rs.getString(2), rs.getInt(3))) }
        }
        runBlocking(Dispatchers.IO) { elasticsearch.reindex(cities) }
    }.onFailure { app.log.warn("Elasticsearch is unavailable; city search will retry on the next restart", it) }

    routing {
        authenticate("auth-session") {
        // Main demo page
        get("/demo") {
            if (!call.requirePermission(Permission.CRUD_READ)) return@get
            val cities = withContext(Dispatchers.IO) {
                val stmt = dbConnection.createStatement()
                val rs = stmt.executeQuery("SELECT id, name, population FROM cities ORDER BY id")
                val list = mutableListOf<Triple<Int, String, Int>>()
                while (rs.next()) {
                    list.add(Triple(rs.getInt(1), rs.getString(2), rs.getInt(3)))
                }
                list
            }
            call.respondText(
                """<!DOCTYPE html>
<html>
<head><title>Ktor Demo — PostgreSQL + RabbitMQ</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         background: #f5f7fa; color: #2d3748; padding: 2rem; max-width: 900px; margin: 0 auto; }
  h1 { color: #2b6cb0; margin-bottom: 0.5rem; }
  .subtitle { color: #718096; margin-bottom: 1.5rem; }
  .card { background: white; border-radius: 8px; padding: 1.5rem; margin-bottom: 1.5rem;
          box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
  h2 { color: #2d3748; margin-bottom: 1rem; font-size: 1.25rem; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid #e2e8f0; }
  th { background: #edf2f7; font-weight: 600; }
  tr:hover { background: #f7fafc; }
  .btn { background: #3182ce; color: white; border: none; padding: 0.5rem 1rem;
         border-radius: 4px; cursor: pointer; font-size: 0.875rem; margin-right: 0.5rem; }
  .btn:hover { background: #2b6cb0; }
  .btn-green { background: #38a169; }
  .btn-green:hover { background: #2f855a; }
  .btn-orange { background: #dd6b20; }
  .btn-orange:hover { background: #c05621; }
  .btn-purple { background: #805ad5; }
  .btn-purple:hover { background: #6b46c1; }
  .output { background: #1a202c; color: #68d391; padding: 1rem; border-radius: 4px;
            font-family: 'Monaco', 'Consolas', monospace; font-size: 0.8rem;
            white-space: pre-wrap; max-height: 300px; overflow-y: auto;
            display: none; margin-top: 1rem; }
  .status { padding: 0.5rem 1rem; border-radius: 4px; margin-top: 1rem; display: none; }
  .status.success { background: #c6f6d5; color: #22543d; display: block; }
  .status.error { background: #fed7d7; color: #742a2a; display: block; }
</style>
</head>
<body>
  <h1>Ktor Demo</h1>
  <p class="subtitle">PostgreSQL + RabbitMQ integrated application</p>

  <div class="card">
    <h2>🏙️ Cities in Database (${cities.size} cities)</h2>
    <table>
      <tr><th>ID</th><th>Name</th><th>Population</th></tr>
      ${cities.joinToString("\n") { "<tr><td>${it.first}</td><td>${it.second}</td><td>${it.third}</td></tr>" }}
      ${if (cities.isEmpty()) "<tr><td colspan='3' style='text-align:center;color:#a0aec0;'>No cities yet</td></tr>" else ""}
    </table>
  </div>

  <div class="card">
    <h2>📦 RabbitMQ Messages</h2>
    <div id="ws-status" style="margin-bottom: 0.5rem; font-size: 0.85rem; color: #a0aec0;">
      <span id="ws-indicator">⚪</span> Connecting to RabbitMQ...
    </div>
    <div id="output-consumed" class="output" style="min-height: 60px;">Waiting for messages...</div>
  </div>

  <div class="card">
    <h2>🔧 Operations</h2>
    <button class="btn" onclick="createCity()">Create City</button>
    <button class="btn btn-green" onclick="refreshCities()">Refresh Cities</button>
    <button class="btn btn-orange" onclick="publishMessage()">Publish to RabbitMQ</button>
    <button class="btn" onclick="listCities()">List All Cities (API)</button>

    <div id="status" class="status"></div>
    <div id="output" class="output"></div>
  </div>

<script>
function showStatus(msg, type) {
  const el = document.getElementById('status');
  el.textContent = msg;
  el.className = 'status ' + type;
}

function showOutput(msg) {
  const el = document.getElementById('output');
  el.textContent = msg;
  el.style.display = 'block';
}

function showConsumedOutput(msg) {
  const el = document.getElementById('output-consumed');
  el.textContent = msg;
  el.style.display = 'block';
}
let csrfToken = null;
async function csrfHeaders() {
  if (!csrfToken) {
    const response = await fetch('/auth/csrf');
    if (!response.ok) throw new Error('Unable to initialize request security');
    csrfToken = (await response.json()).token;
  }
  return { 'X-CSRF-Token': csrfToken };
}

// WebSocket connection for real-time RabbitMQ messages
var ws = null;
function connectWebSocket() {
  var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  var wsUrl = protocol + '//' + window.location.host + '/demo/ws';
  ws = new WebSocket(wsUrl);

  ws.onopen = function() {
    document.getElementById('ws-indicator').textContent = '🟢';
    document.getElementById('ws-status').querySelector('span').nextSibling.textContent = ' Connected!';
  };

  ws.onclose = function() {
    document.getElementById('ws-indicator').textContent = '🔴';
    document.getElementById('ws-status').querySelector('span').nextSibling.textContent = ' Disconnected';
    // Retry after 3 seconds
    setTimeout(connectWebSocket, 3000);
  };

  ws.onmessage = function(event) {
    const data = JSON.parse(event.data);
    const el = document.getElementById('output-consumed');
    el.textContent = el.textContent === 'Waiting for messages...' ? data.body : el.textContent + '\n' + data.body;
    el.style.display = 'block';
  };

  ws.onerror = function(error) {
    document.getElementById('ws-indicator').textContent = '🔴';
    document.getElementById('ws-status').querySelector('span').nextSibling.textContent = ' Error';
  };
}

// Start WebSocket connection on page load
connectWebSocket();
function hideOutputs() {
  document.getElementById('status').style.display = 'none';
  document.getElementById('output').style.display = 'none';
}

async function createCity() {
  hideOutputs();
  try {
    const securityHeaders = await csrfHeaders();
    const res = await fetch('/demo/cities', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...securityHeaders },
      body: JSON.stringify({ name: 'Berlin', population: 3670000 })
    });
    const text = await res.text();
    showStatus('POST /demo/cities → ' + res.status + ' ' + text, 'success');
    refreshCities();
  } catch(e) { showStatus('Error: ' + e.message, 'error'); }
}

async function refreshCities() {
  hideOutputs();
  try {
    const res = await fetch('/demo/cities');
    const data = await res.json();
    showStatus('Cities refreshed: ' + data.length + ' cities', 'success');
    showOutput(JSON.stringify(data, null, 2));
  } catch(e) { showStatus('Error: ' + e.message, 'error'); }
}

async function publishMessage() {
  hideOutputs();
  try {
    const res = await fetch('/demo/rabbitmq/publish', { method: 'POST', headers: await csrfHeaders() });
    const text = await res.text();
    if (res.ok) {
      showStatus('RabbitMQ message published to test-exchange (watch for it in the messages panel)', 'success');
    } else {
      showStatus('RabbitMQ error: ' + text, 'error');
      return;
    }
  } catch(e) { showStatus('Error: ' + e.message, 'error'); }
}

async function listCities() {
  hideOutputs();
  try {
    const res = await fetch('/demo/cities');
    const data = await res.json();
    showOutput(JSON.stringify(data, null, 2));
  } catch(e) { showStatus('Error: ' + e.message, 'error'); }
}

async function viewConsumedMessages() {
  try {
    const res = await fetch('/demo/rabbitmq/consumed');
    const data = await res.json();
    if (data.length === 0) {
      showConsumedOutput('No messages consumed yet.\n\n1. Click "Publish to RabbitMQ" to send a message.\n2. The consumer picks it up automatically.\n3. The message body will appear here.');
    } else {
      showConsumedOutput(data.join('\n'));
    }
  } catch(e) { showStatus('Error: ' + e.message, 'error'); }
}
</script>
</body>
</html>""".trimIndent(),
                ContentType.Text.Html
            )
        }

        rabbitmq {
            post("/demo/cities") {
                if (!call.requirePermission(Permission.CRUD_CREATE) || !call.requireCsrfToken()) return@post
                val user = call.currentAuthenticatedUser() ?: return@post
                val city = call.receive<CityCommand>().copy(
                    operation = "create",
                    id = null,
                    conversationId = null,
                    actorId = user.id,
                )
                basicPublish { exchange = "test-exchange"; routingKey = "test-routing-key"; message { CityChangeProcessor.payload(city) } }
                call.respondText("City change queued")
            }
            put("/demo/cities/{id}") {
                if (!call.requirePermission(Permission.CRUD_UPDATE) || !call.requireCsrfToken()) return@put
                val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid id")
                val user = call.currentAuthenticatedUser() ?: return@put
                val city = call.receive<CityCommand>().copy(
                    operation = "update",
                    id = id,
                    conversationId = null,
                    actorId = user.id,
                )
                basicPublish { exchange = "test-exchange"; routingKey = "test-routing-key"; message { CityChangeProcessor.payload(city) } }
                call.respondText("City change queued")
            }
            delete("/demo/cities/{id}") {
                if (!call.requirePermission(Permission.CRUD_DELETE) || !call.requireCsrfToken()) return@delete
                val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid id")
                val user = call.currentAuthenticatedUser() ?: return@delete
                val command = CityCommand("delete", id, actorId = user.id)
                basicPublish { exchange = "test-exchange"; routingKey = "test-routing-key"; message { CityChangeProcessor.payload(command) } }
                call.respondText("City change queued")
            }
        }

        // POST /demo/rabbitmq/publish - publish a message to RabbitMQ
        rabbitmq {
            post("/demo/rabbitmq/publish") {
                if (!call.requirePermission(Permission.CRUD_CREATE) || !call.requireCsrfToken()) return@post
                basicPublish {
                    exchange = "test-exchange"
                    routingKey = "test-routing-key"
                    properties = basicProperties { correlationId = "demo-${System.currentTimeMillis()}" }
                    message { "Hello from Ktor demo! Time: ${System.currentTimeMillis()}" }
                }
                call.respondText("Message published to RabbitMQ (test-exchange)", ContentType.Text.Plain)
            }
        }

        with(cityAgent) { routes() }

        // GET /demo/cities - list all cities (authenticated only)
        get("/demo/cities") {
            val stmt = dbConnection.createStatement()
            val rs = stmt.executeQuery("SELECT id, name, population FROM cities ORDER BY id")
            call.respond(buildList {
                while (rs.next()) add(CityView(rs.getInt(1), rs.getString(2), rs.getInt(3)))
            })
        }

        get("/demo/cities/search") {
            val name = call.request.queryParameters["name"].orEmpty().trim()
            call.respond(
                if (name.isEmpty()) emptyList() else runCatching {
                    withContext(Dispatchers.IO) { elasticsearch.searchByName(name) }
                }.getOrElse { throw IllegalStateException("City search is unavailable", it) }
            )
        }

        // GET /demo/rabbitmq/consumed - list messages consumed by the consumer
        get("/demo/rabbitmq/consumed") {
            if (!call.requirePermission(Permission.CRUD_READ)) return@get
            call.respond(ConsumerState.consumedMessages.toList())
        }
        }
    }
}
