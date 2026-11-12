package com.example

import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun Application.configureWebsockets() {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(DefaultJson)
    }

    val sessions = mutableSetOf<WebSocketServerSession>()
    val lock = Any()
    val appScope = CoroutineScope(coroutineContext)

    routing {
        webSocket("/demo/ws") {
            synchronized(lock) {
                sessions.add(this)
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Close) {
                        break
                    }
                }
            } finally {
                synchronized(lock) {
                    sessions.remove(this)
                }
            }
        }
    }

    appScope.launch {
        for (msg in ConsumerState.messages) {
            val snapshot = synchronized(lock) { sessions.toList() }
            snapshot.forEach { session ->
                try {
                    session.sendSerialized(msg)
                } catch (_: Exception) {
                    // Session may have closed
                }
            }
        }
    }
}
