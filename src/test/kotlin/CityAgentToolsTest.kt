package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import kotlinx.coroutines.runBlocking
import java.time.Instant

class CityAgentToolsTest {
    private val cities = listOf(
        CityView(id = 1, name = "Oslo", population = 709037),
        CityView(id = 2, name = "Oslo East", population = 1200),
    )
    private val conversations = AgentConversationRegistry()
    private val commands = mutableListOf<CityCommand>()
    private val tools = CityAgentTools(
        conversations = conversations,
        searchCities = { query -> cities.filter { it.name.contains(query, ignoreCase = true) } },
        listCities = { cities },
        enqueueChange = commands::add,
    )

    @Test
    fun `missing create population asks follow-up and does not persist`() {
        val answer = tools.createCity("Reykjavik", -1, "conversation-1")

        assertEquals("What is the population for \"Reykjavik\"?", answer)
        assertTrue(commands.isEmpty())
        assertEquals("Reykjavik", conversations.get("conversation-1").pendingCreateName)
    }

    @Test
    fun `population follow-up completes pending create`() {
        tools.createCity("Reykjavik", -1, "conversation-1")

        val answer = tools.completePendingCreate("conversation-1", 139875)

        assertEquals("Queued creation of Reykjavik with a population of 139875.", answer)
        assertEquals(
            CityCommand("create", name = "Reykjavik", population = 139875, conversationId = "conversation-1"),
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
        val answer = tools.updateCity("Oslo", "Oslo", 710000, "conversation-1")

        assertEquals("Queued update of Oslo to Oslo with a population of 710000.", answer)
        assertEquals(
            CityCommand("update", id = 1, name = "Oslo", population = 710000, conversationId = "conversation-1"),
            commands.single(),
        )
    }

    @Test
    fun `delete rejects a partial name match without changing a city`() {
        val answer = tools.deleteCity("Os", "conversation-1")

        assertEquals("I couldn't find a city named Os.", answer)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `missing population follow-up does not enqueue create`() {
        tools.createCity("Reykjavik", -1, "conversation-1")

        val answer = tools.completePendingCreate("conversation-1", -1)

        assertEquals("I cannot complete creating Reykjavik without a non-negative whole-number population. What is its population?", answer)
        assertTrue(commands.isEmpty())
        assertEquals("Reykjavik", conversations.get("conversation-1").pendingCreateName)
    }

    @Test
    fun `city context tools provide all cities and a specific current population`() {
        assertEquals("Oslo: 709037\nOslo East: 1200", tools.listCities())
        assertEquals(
            "Oslo exists and its current registered population is 709037.",
            tools.lookupCityByName("Oslo"),
        )
    }

    @Test
    fun `successful city operations are retained in order and available to the assistant`() {
        tools.recordMessage("conversation-1", "user", "Create Stockholm")
        tools.createCity("Stockholm", 100000, "conversation-1")
        tools.recordMessage("conversation-1", "assistant", "Queued Stockholm.")
        tools.recordMessage("conversation-1", "user", "Remove Oslo")
        tools.updateCity("Oslo", "Oslo", 710000, "conversation-1")
        tools.deleteCity("Oslo", "conversation-1")

        val history = tools.operationHistory("conversation-1")
        assertTrue(history.contains("User: Create Stockholm"))
        assertTrue(history.contains("City operation (queued): Created Stockholm with population 100000"))
        assertTrue(history.contains("Assistant: Queued Stockholm."))
        assertTrue(history.contains("User: Remove Oslo"))
        assertTrue(history.contains("City operation (queued): Updated Oslo to Oslo: population 709037 → 710000"))
        assertTrue(history.contains("City operation (queued): Deleted Oslo with population 709037"))
    }

    @Test
    fun `operation history includes committed dashboard deletions`() {
        CityOperationHistory.record(
            CityOperationRecord("Deleted Helsinki with population 694000", Instant.now(), null),
        )

        val history = tools.operationHistory("conversation-with-dashboard-delete")

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
}
