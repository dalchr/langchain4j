package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.exception.ToolErrorVisibleToLlmException
import kotlinx.coroutines.runBlocking
import java.time.Instant

class CityAgentToolsTest {
    private val cities = listOf(
        CityView(id = 1, name = "Oslo", population = 709037),
        CityView(id = 2, name = "Oslo East", population = 1200),
    )
    private val conversations = AgentConversationRegistry()
    private val commands = mutableListOf<CityCommand>()
    private val user = AuthenticatedUser("local:test-user", "test-user", "Test User")
    private val testConnection = java.sql.DriverManager.getConnection("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "")
    private val testService = CityService(testConnection)
    private val tools = CityAgentTools(
        log = org.slf4j.LoggerFactory.getLogger("CityAgentToolsTest"),
        conversations = conversations,
        cityService = testService,
        searchCities = { query -> cities.filter { it.name.contains(query, ignoreCase = true) } },
        listCities = { cities },
        enqueueChange = commands::add,
    )

    @Test
    fun `missing create population asks follow-up and does not persist`() {
        val answer = asUser { tools.createCity("Reykjavik", -1, "conversation-1") }

        assertEquals("What is the population for \"Reykjavik\"?", answer)
        assertTrue(commands.isEmpty())
        assertEquals("Reykjavik", conversations.get("conversation-1").pendingCreateName)
    }

    @Test
    fun `population follow-up completes pending create`() {
        asUser { tools.createCity("Reykjavik", -1, "conversation-1") }

        val answer = asUser { tools.completePendingCreate("conversation-1", 139875) }

        assertEquals("Queued creation of Reykjavik with a population of 139875.", answer)
        assertEquals(
            CityCommand(
                "create",
                name = "Reykjavik",
                population = 139875,
                conversationId = "conversation-1",
                actorId = user.id,
            ),
            commands.single(),
        )
        assertNull(conversations.get("conversation-1").pendingCreateName)
    }

    @Test
    fun `population follow-up accepts an unambiguous number in a sentence`() {
        assertEquals(1_000_000, populationFromReply("population is 1,000,000"))
        assertEquals(1_000_000, populationFromReply("same as before 1000000"))
        assertEquals(900_000, populationFromReply("try 900000"))
        assertNull(populationFromReply("population is one million"))
        assertNull(populationFromReply("use 900000 or 1000000"))
        assertNull(populationFromReply("-1"))
    }

    @Test
    fun `update resolves exact city name and passes resolved id to persistence`() {
        val answer = asUser { tools.updateCity("Oslo", "Oslo", 710000, -1, "conversation-1") }

        assertEquals("Queued update of Oslo to Oslo with a population of 710000.", answer)
        assertEquals(
            CityCommand(
                "update",
                id = 1,
                name = "Oslo",
                population = 710000,
                conversationId = "conversation-1",
                actorId = user.id,
            ),
            commands.single(),
        )
    }

    @Test
    fun `delete rejects a partial name match without changing a city`() {
        val answer = asUser { tools.deleteCity("Os", -1, "conversation-1") }

        assertEquals("I couldn't find a city named Os.", answer)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `missing population follow-up does not enqueue create`() {
        asUser { tools.createCity("Reykjavik", -1, "conversation-1") }

        val answer = asUser { tools.completePendingCreate("conversation-1", -1) }

        assertEquals("I cannot complete creating Reykjavik without a non-negative whole-number population. What is its population?", answer)
        assertTrue(commands.isEmpty())
        assertEquals("Reykjavik", conversations.get("conversation-1").pendingCreateName)
    }

    @Test
    fun `city context tools provide all cities and a specific current population`() {
        assertEquals("Oslo: 709037\nOslo East: 1200", asUser { tools.listCities("conversation-1") })
        assertEquals(
            "Oslo exists and its current registered population is 709037.",
            asUser { tools.lookupCityByName("Oslo", "conversation-1") },
        )
    }

    @Test
    fun `successful city operations are retained in order and available to the assistant`() {
        asUser {
            tools.recordMessage("conversation-1", "user", "Create Stockholm")
            tools.createCity("Stockholm", 100000, "conversation-1")
            tools.recordMessage("conversation-1", "assistant", "Queued Stockholm.")
            tools.recordMessage("conversation-1", "user", "Remove Oslo")
            tools.updateCity("Oslo", "Oslo", 710000, -1, "conversation-1")
            tools.deleteCity("Oslo", -1, "conversation-1")

            val history = tools.operationHistory("conversation-1")
            assertTrue(history.contains("User: Create Stockholm"))
            assertTrue(history.contains("City operation (queued): Created Stockholm with population 100000"))
            assertTrue(history.contains("Assistant: Queued Stockholm."))
            assertTrue(history.contains("User: Remove Oslo"))
            assertTrue(history.contains("City operation (queued): Updated Oslo to Oslo: population 709037 → 710000"))
            assertTrue(history.contains("City operation (queued): Deleted Oslo with population 709037"))
            assertTrue(
                conversations.get("conversation-1").activityHistory
                    .filter { it.role == "city_operation" }
                    .all { it.actorId == user.id },
            )
        }
    }

    @Test
    fun `operation history includes committed dashboard deletions`() {
        CityOperationHistory.record(
            CityOperationRecord("Deleted Helsinki with population 694000", Instant.now(), null),
        )

        val history = asUser("conversation-with-dashboard-delete") {
            tools.operationHistory("conversation-with-dashboard-delete")
        }

        assertTrue(history.contains("City operation (committed outside the assistant): Deleted Helsinki with population 694000"))
    }

    @Test
    fun `conversation reset clears history and pending create`() = runBlocking {
        val conversation = conversations.get("conversation-1")
        conversation.memory.add(UserMessage.from("previous city question"))
        conversation.pendingCreateName = "Reykjavik"

        conversations.reset("conversation-1")

        assertTrue(conversation.memory.messages().isEmpty())
        assertNull(conversation.pendingCreateName)
        assertTrue(conversation.activityHistory.isEmpty())
    }

    @Test
    fun `unknown tool names are returned to the model for recovery instead of failing`() {
        val result = recoverFromUnknownCityTool(
            ToolExecutionRequest.builder()
                .id("call-1")
                .name("createCity<|channel|>json")
                .arguments("{\"name\":\"London\",\"population\":10000000}")
                .build(),
        )

        assertEquals("createCity<|channel|>json", result.toolName())
        assertTrue(result.text().contains("createCity, updateCity, deleteCity"))
        assertTrue(result.text().contains("Retry the requested action"))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `city mutation tools reject calls without authenticated execution context`() {
        val exception = runCatching { tools.deleteCity("Oslo", -1, "conversation-1") }.exceptionOrNull()

        assertTrue(exception is ToolErrorVisibleToLlmException)
        assertTrue(exception?.message?.contains("Please log in") == true)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `city mutation tools reject users without permission before enqueueing`() {
        val reader = user.copy(roles = listOf("READER"))
        val exception = runCatching {
            asUser("conversation-1", reader) {
                tools.deleteCity("Oslo", -1, "conversation-1")
            }
        }.exceptionOrNull()

        assertTrue(exception is ToolErrorVisibleToLlmException)
        assertTrue(exception?.message?.contains("not allowed") == true)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `agent conversation keys are isolated by authenticated user`() {
        val otherUser = user.copy(id = "local:other-user")

        assertTrue(agentConversationKey(user, "same-id") != agentConversationKey(otherUser, "same-id"))
    }


    @Test
    fun `updateCities bulk updates multiple cities successfully`() {
        val answer = asUser {
            tools.updateCities(
                listOf(
                    CityPopulationUpdate("Oslo", 720000),
                    CityPopulationUpdate("Oslo East", 1500),
                ),
                "conversation-1",
            )
        }

        assertTrue(answer.startsWith("Updated 2 cities."))
        assertTrue(answer.contains("Oslo"))
        assertTrue(answer.contains("Oslo East"))
        assertEquals(2, commands.size)
        assertEquals("update", commands[0].operation)
        assertEquals(720000, commands[0].population)
        assertEquals("update", commands[1].operation)
        assertEquals(1500, commands[1].population)
    }

    @Test
    fun `updateCities handles mixed success and failure gracefully`() {
        val answer = asUser {
            tools.updateCities(
                listOf(
                    CityPopulationUpdate("Oslo", 720000),
                    CityPopulationUpdate("Nonexistent", 500),
                ),
                "conversation-1",
            )
        }

        assertTrue(answer.startsWith("Updated 1 city."))
        assertTrue(answer.contains("Oslo"))
        assertTrue(answer.contains("Nonexistent"))
        assertTrue(answer.contains("couldn't find") || answer.contains("not found"))
        assertEquals(1, commands.size)
        assertEquals("update", commands[0].operation)
        assertEquals(720000, commands[0].population)
    }

    @Test
    fun `updateCities handles empty name gracefully`() {
        val answer = asUser {
            tools.updateCities(
                listOf(
                    CityPopulationUpdate("Oslo", 720000),
                    CityPopulationUpdate("", 500),
                ),
                "conversation-1",
            )
        }

        assertTrue(answer.startsWith("Updated 1 city."))
        assertTrue(answer.contains("Skipped empty city name"))
    }

    @Test
    fun `updateCities with empty list returns zero count`() {
        val answer = asUser {
            tools.updateCities(emptyList(), "conversation-1")
        }

        assertEquals("Updated 0 cities. ", answer)
    }

    @Test
    fun `updateCities rejects without authenticated execution context`() {
        val exception = runCatching {
            tools.updateCities(
                listOf(CityPopulationUpdate("Oslo", 720000)),
                "conversation-1",
            )
        }.exceptionOrNull()

        assertTrue(exception is ToolErrorVisibleToLlmException)
        assertTrue(exception?.message?.contains("Please log in") == true)
    }

    private fun <T> asUser(
        conversationId: String = "conversation-1",
        authenticatedUser: AuthenticatedUser = user,
        action: () -> T,
    ): T {
        val executionContext = tools.bindExecutionContext(conversationId, authenticatedUser)
        return try {
            action()
        } finally {
            executionContext.close()
        }
    }
}
