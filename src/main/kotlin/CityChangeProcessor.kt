package com.example

import io.github.damir.denis.tudor.ktor.server.rabbitmq.dsl.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection

@Serializable
data class CityCommand(val operation: String, val id: Int? = null, val name: String = "", val population: Int = 0)

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