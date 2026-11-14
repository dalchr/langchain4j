package com.example

import io.github.damir.denis.tudor.ktor.server.rabbitmq.dsl.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.Instant
import java.util.Collections

@Serializable
data class CityCommand(
    val operation: String,
    val id: Int? = null,
    val name: String = "",
    val population: Int = 0,
    val ctx: ActorContext = ActorContext(),
)

internal data class CityOperationRecord(
    val description: String,
    val timestamp: Instant,
    val ctx: ActorContext = ActorContext(),
)

internal object CityOperationHistory {
    private const val MAX_ENTRIES = 500
    private val entries = Collections.synchronizedList(mutableListOf<CityOperationRecord>())

    fun record(record: CityOperationRecord) {
        synchronized(entries) {
            if (entries.size == MAX_ENTRIES) entries.removeAt(0)
            entries.add(record)
        }
    }

    fun snapshot(): List<CityOperationRecord> = synchronized(entries) { entries.toList() }
}

object CityChangeProcessor {
    lateinit var connectionProvider: () -> Connection
    lateinit var search: ElasticsearchClient

    fun payload(command: CityCommand): String = "CITY_CHANGE:" + Json.encodeToString(command)

    @Synchronized
    fun apply(command: CityCommand) {
        check(::connectionProvider.isInitialized && ::search.isInitialized) { "City change processor is not initialized" }

        connectionProvider().use { connection ->
            try {
                connection.autoCommit = false
                val previous = command.id?.let { id ->
                    connection.prepareStatement("SELECT name, population FROM cities WHERE id = ?").use {
                        it.setInt(1, id)
                        it.executeQuery().use { result ->
                            if (result.next()) CityView(id, result.getString(1), result.getInt(2)) else null
                        }
                    }
                }
                when (command.operation) {
                    "create" -> connection.prepareStatement("INSERT INTO cities (name, population) VALUES (?, ?)").use {
                        it.setString(1, command.name)
                        it.setInt(2, command.population)
                        it.executeUpdate()
                    }
                    "update" -> connection.prepareStatement("UPDATE cities SET name = ?, population = ? WHERE id = ?").use {
                        it.setString(1, command.name)
                        it.setInt(2, command.population)
                        it.setInt(3, requireNotNull(command.id) { "Update requires an id" })
                        check(it.executeUpdate() == 1) { "City ${command.id} was not found" }
                    }
                    "delete" -> connection.prepareStatement("DELETE FROM cities WHERE id = ?").use {
                        it.setInt(1, requireNotNull(command.id) { "Delete requires an id" })
                        check(it.executeUpdate() == 1) { "City ${command.id} was not found" }
                    }
                    else -> error("Unknown city operation: ${command.operation}")
                }
                connection.commit()

                if (command.ctx.conversationId == null) {
                    val description = when (command.operation) {
                        "create" -> "Created ${command.name} with population ${command.population}"
                        "update" -> "Updated ${previous?.name ?: command.id} to ${command.name}: population ${previous?.population ?: "unknown"} → ${command.population}"
                        "delete" -> "Deleted ${previous?.name ?: "city ${command.id}"} with population ${previous?.population ?: "unknown"}"
                        else -> error("Unknown city operation: ${command.operation}")
                    }
                    CityOperationHistory.record(CityOperationRecord(description, Instant.now(), command.ctx))
                }

                val cities = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, name, population FROM cities").use { result ->
                    buildList {
                        while (result.next()) add(CityView(result.getInt(1), result.getString(2), result.getInt(3)))
                    }
                }
                }
                runCatching { search.reindex(cities) }
                    .onFailure { error -> println("Unable to reindex cities after ${command.operation}: ${error.message}") }
                ConsumerState.onMessage("city-changed")
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            }
        }
    }
}