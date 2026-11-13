package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(CityCommand("create", name = "Reykjavik", population = 139875), commands.single())
        assertNull(conversations.get("conversation-1").pendingCreateName)
    }

    @Test
    fun `update resolves exact city name and passes resolved id to persistence`() {
        val answer = tools.updateCity("Oslo", "Oslo", 710000)

        assertEquals("Queued update of Oslo to Oslo with a population of 710000.", answer)
        assertEquals(CityCommand("update", id = 1, name = "Oslo", population = 710000), commands.single())
    }

    @Test
    fun `delete rejects a partial name match without changing a city`() {
        val answer = tools.deleteCity("Os")

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
}
