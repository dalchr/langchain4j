package com.example

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Statement

@Serializable
data class City(val name: String, val population: Int)

@Serializable
data class CityView(val id: Int, val name: String, val population: Int)

@Serializable
data class CityChange(
    val cityId: Int,
    val name: String,
    val beforePopulation: Int,
    val afterPopulation: Int,
)

@Serializable
data class UpdateResult(
    val affectedCount: Int,
    val changes: List<CityChange>,
)

@Serializable
data class OperationRecord(
    val id: Int,
    val operationType: String,
    val delta: Int?,
    val affectedCount: Int,
    val createdAt: String,
    val status: String,
    val changesJson: String?,
    val conversationId: String? = null,
)

@Serializable
data class OperationHistory(
    val id: Int,
    val operationType: String,
    val description: String,
    val createdAt: String,
    val conversationId: String? = null,
    val actorId: String? = null,
)

private val json = Json { encodeDefaults = true }

class CityService(private val connection: Connection) {
    companion object {
        private const val CREATE_TABLE_CITIES =
            "CREATE TABLE IF NOT EXISTS CITIES (ID SERIAL PRIMARY KEY, NAME VARCHAR(255), POPULATION INT);"
        private const val CREATE_TABLE_OPERATIONS =
            "CREATE TABLE IF NOT EXISTS CITY_OPERATIONS (" +
            "  ID SERIAL PRIMARY KEY," +
            "  OPERATION_TYPE VARCHAR(50)," +
            "  DELTA INT," +
            "  AFFECTED_COUNT INT," +
            "  CREATED_AT TIMESTAMP DEFAULT NOW()," +
            "  STATUS VARCHAR(20)," +
            "  CHANGES_JSON TEXT," +
            "  CONVERSATION_ID VARCHAR(512)," +
            "  ACTOR_ID VARCHAR(255)," +
            "  DESCRIPTION TEXT" +
            ");"
        private const val SELECT_CITY_BY_ID = "SELECT name, population FROM cities WHERE id = ?"
        private const val INSERT_CITY = "INSERT INTO cities (name, population) VALUES (?, ?)"
        private const val UPDATE_CITY = "UPDATE cities SET name = ?, population = ? WHERE id = ?"
        private const val DELETE_CITY = "DELETE FROM cities WHERE id = ?"
        private const val SELECT_ALL_CITIES = "SELECT id, name, population FROM cities ORDER BY id"
        private const val INSERT_OPERATION =
            "INSERT INTO city_operations (operation_type, delta, affected_count, status, changes_json, conversation_id, actor_id, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        private const val SELECT_LAST_COMMITTED_OPERATION =
            "SELECT id, operation_type, delta, affected_count, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'), status, changes_json, conversation_id FROM city_operations WHERE status = 'COMMITTED' ORDER BY id DESC LIMIT 1"
        private const val SELECT_ALL_OPERATIONS =
            "SELECT id, operation_type, delta, affected_count, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'), status, changes_json, conversation_id FROM city_operations ORDER BY id DESC"
    }

    init {
        val statement = connection.createStatement()
        statement.executeUpdate(CREATE_TABLE_CITIES)
        statement.executeUpdate(CREATE_TABLE_OPERATIONS)
    }

    // Create new city
    suspend fun create(city: City): Int = withContext(Dispatchers.IO) {
        val statement = connection.prepareStatement(INSERT_CITY, Statement.RETURN_GENERATED_KEYS)
        statement.setString(1, city.name)
        statement.setInt(2, city.population)
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            return@withContext generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted city")
        }
    }

    // Read a city
    suspend fun read(id: Int): City = withContext(Dispatchers.IO) {
        val statement = connection.prepareStatement(SELECT_CITY_BY_ID)
        statement.setInt(1, id)
        val resultSet = statement.executeQuery()

        if (resultSet.next()) {
            val name = resultSet.getString("name")
            val population = resultSet.getInt("population")
            return@withContext City(name, population)
        } else {
            throw Exception("Record not found")
        }
    }

    // Update a city
    suspend fun update(id: Int, city: City) = withContext(Dispatchers.IO) {
        val statement = connection.prepareStatement(UPDATE_CITY)
        statement.setString(1, city.name)
        statement.setInt(2, city.population)
        statement.setInt(3, id)
        statement.executeUpdate()
    }

    // Delete a city
    suspend fun delete(id: Int) = withContext(Dispatchers.IO) {
        val statement = connection.prepareStatement(DELETE_CITY)
        statement.setInt(1, id)
        statement.executeUpdate()
    }

    // Read all cities
    fun readAll(): List<CityView> {
        val statement = connection.createStatement()
        val resultSet = statement.executeQuery(SELECT_ALL_CITIES)
        return buildList {
            while (resultSet.next()) add(CityView(resultSet.getInt(1), resultSet.getString(2), resultSet.getInt(3)))
        }
    }

    /**
     * Apply a population delta to every city atomically.
     * Records the operation in [city_operations] with before/after values.
     */
    fun updateAllPopulation(delta: Int): UpdateResult {
        val changes = mutableListOf<CityChange>()
        try {
            connection.autoCommit = false

            val allCities = readAll()
            val stmtUpdate = connection.prepareStatement(UPDATE_CITY)
            val stmtInsert = connection.prepareStatement(INSERT_OPERATION, Statement.RETURN_GENERATED_KEYS)

            for (city in allCities) {
                val before = city.population
                val after = before + delta
                stmtUpdate.setString(1, city.name)
                stmtUpdate.setInt(2, after)
                stmtUpdate.setInt(3, city.id)
                stmtUpdate.executeUpdate()
                changes.add(CityChange(city.id, city.name, before, after))
            }

            val changesJson = json.encodeToString(changes)
            stmtInsert.setString(1, "UPDATE_ALL")
            stmtInsert.setInt(2, delta)
            stmtInsert.setInt(3, changes.size)
            stmtInsert.setString(4, "COMMITTED")
            stmtInsert.setString(5, changesJson)
            stmtInsert.setString(6, null)
            stmtInsert.setString(7, null)
            stmtInsert.setString(8, "Applied population delta of $delta to ${changes.size} cities")
            stmtInsert.executeUpdate()

            connection.commit()

            return UpdateResult(changes.size, changes)
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Reverse the last committed [UPDATE_ALL] operation by applying the inverse delta.
     */
    fun undoLastOperation(): UpdateResult {
        val lastOp = getLastCommittedOperation() ?: throw IllegalStateException("No committed operation to undo")
        val inverseDelta = -(lastOp.delta ?: 0)
        return updateAllPopulation(inverseDelta).also { result ->
            // Record the undo in operation history
            val stmtInsert = connection.prepareStatement(INSERT_OPERATION)
            stmtInsert.setString(1, "UNDO")
            stmtInsert.setInt(2, inverseDelta)
            stmtInsert.setInt(3, result.affectedCount)
            stmtInsert.setString(4, "COMMITTED")
            stmtInsert.setString(5, null)
            stmtInsert.setString(6, null)
            stmtInsert.setString(7, null)
            stmtInsert.setString(8, "Undo of operation #${lastOp.id} (delta ${lastOp.delta} → $inverseDelta)")
            stmtInsert.executeUpdate()
        }
    }

    /**
     * Persist all current city states as a named snapshot.
     */
    fun createSnapshot(name: String): Int {
        // Reuse city_operations table with a SNAPSHOT type
        val cities = readAll()
        val changesJson = json.encodeToString(
            cities.map { CityChange(it.id, it.name, it.population, it.population) }
        )
        val stmt = connection.prepareStatement(INSERT_OPERATION, Statement.RETURN_GENERATED_KEYS)
        stmt.setString(1, "SNAPSHOT")
        stmt.setInt(2, 0)
        stmt.setInt(3, cities.size)
        stmt.setString(4, "COMMITTED")
        stmt.setString(5, changesJson)
        stmt.setString(6, null)
        stmt.setString(7, null)
        stmt.setString(8, "Created snapshot '$name' with ${cities.size} cities")
        stmt.executeUpdate()

        val keys = stmt.generatedKeys
        return if (keys.next()) keys.getInt(1) else 0
    }

    /**
     * List all operations ordered by ID descending.
     */
    fun listOperations(): List<OperationRecord> {
        val stmt = connection.createStatement()
        val rs = stmt.executeQuery(SELECT_ALL_OPERATIONS)
        return buildList {
            while (rs.next()) {
                add(
                    OperationRecord(
                        id = rs.getInt(1),
                        operationType = rs.getString(2),
                        delta = rs.getInt(3).takeIf { !rs.wasNull() },
                        affectedCount = rs.getInt(4),
                        createdAt = rs.getString(5),
                        status = rs.getString(6),
                        changesJson = rs.getString(7),
                        conversationId = rs.getString(8),
                    ),
                )
            }
        }
    }

    /**
     * List human-readable operation history entries (simplified).
     */
    fun listOperationHistory(): List<OperationHistory> {
        val ops = listOperations()
        return ops.map { op ->
            OperationHistory(
                id = op.id,
                operationType = op.operationType,
                description = buildString {
                    append("${op.operationType} - ")
                    if (op.delta != null) append("delta: ${op.delta}, ")
                    append("affected: ${op.affectedCount} cities, ")
                    append("status: ${op.status}, ")
                    append("at: ${op.createdAt}")
                },
                createdAt = op.createdAt,
                conversationId = op.conversationId,
            )
        }
    }

    private fun getLastCommittedOperation(): OperationRecord? {
        val stmt = connection.prepareStatement(SELECT_LAST_COMMITTED_OPERATION)
        val rs = stmt.executeQuery()
        return if (rs.next()) {
            OperationRecord(
                id = rs.getInt(1),
                operationType = rs.getString(2),
                delta = rs.getInt(3).takeIf { !rs.wasNull() },
                affectedCount = rs.getInt(4),
                createdAt = rs.getString(5),
                status = rs.getString(6),
                changesJson = rs.getString(7),
            )
        } else {
            null
        }
    }
}
