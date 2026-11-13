package com.example

import io.github.damir.denis.tudor.ktor.server.rabbitmq.connection.ConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CityCommandPublisher {
    private val mutex = Mutex()
    private var connectionManager: ConnectionManager? = null
    private var connectionName: String? = null

    @Synchronized
    fun configure(manager: ConnectionManager, name: String) {
        connectionManager = manager
        connectionName = name
    }

    suspend fun publish(command: CityCommand) = mutex.withLock {
        val manager = checkNotNull(connectionManager) { "RabbitMQ is not configured" }
        val name = checkNotNull(connectionName) { "RabbitMQ is not configured" }
        manager.getChannel(channelId = 3, connectionId = name).basicPublish(
            exchange = "test-exchange",
            routingKey = "test-routing-key",
            message = CityChangeProcessor.payload(command).toByteArray(Charsets.UTF_8),
        )
    }
}