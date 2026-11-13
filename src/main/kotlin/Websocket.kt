package com.example

import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.*
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

fun Application.configureWebsockets() {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(DefaultJson)
    }

    data class AuthenticatedSocket(val session: AuthSession, val user: AuthenticatedUser)

    val authenticationService = authenticationService()
    val sessions = mutableMapOf<WebSocketServerSession, AuthenticatedSocket>()
    val lock = Any()
    val appScope = CoroutineScope(coroutineContext)

    routing {
        authenticate("auth-session") {
            webSocket("/demo/ws") {
                val authSession = call.sessions.get<AuthSession>()
                val user = authSession?.let(authenticationService::resolve)
                if (authSession == null || user == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }
                synchronized(lock) {
                    sessions[this] = AuthenticatedSocket(authSession, user)
                }

                val sessionMonitor = launch {
                    while (true) {
                        delay(30.seconds)
                        if (authenticationService.resolve(authSession) == null) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session expired or logged out"))
                            break
                        }
                    }
                }
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Close) {
                            break
                        }
                    }
                } finally {
                    sessionMonitor.cancel()
                    synchronized(lock) {
                        sessions.remove(this)
                    }
                }
            }
        }
    }

    appScope.launch {
        for (msg in ConsumerState.messages) {
            val snapshot = synchronized(lock) { sessions.toList() }
            snapshot.forEach { (session, socket) ->
                try {
                    if (authenticationService.resolve(socket.session)?.id != socket.user.id) {
                        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session expired or logged out"))
                        synchronized(lock) { sessions.remove(session) }
                    } else {
                        session.sendSerialized(msg)
                    }
                } catch (_: Exception) {
                    // Session may have closed
                }
            }
        }
    }
}
