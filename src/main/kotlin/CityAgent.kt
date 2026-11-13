package com.example

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.ToolMemoryId
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.MemoryId
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.UserMessage as UserMessageAnnotation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AgentChatRequest(val conversationId: String, val message: String)

@Serializable
data class AgentStreamEvent(val type: String, val content: String? = null, val error: String? = null)

internal data class AgentConversation(
    val memory: ChatMemory,
    val lock: Mutex = Mutex(),
    var pendingCreateName: String? = null,
)

internal class AgentConversationRegistry(
    private val maxMessages: Int = 20,
) {
    private val conversations = ConcurrentHashMap<String, AgentConversation>()

    fun get(id: String): AgentConversation = conversations.computeIfAbsent(id) {
        AgentConversation(MessageWindowChatMemory.builder().id(it).maxMessages(maxMessages).build())
    }
}

private interface CityAssistant {
    @SystemMessage(
        """
        You help manage the cities in this application. Use the available city tools for every requested change.
        For update and delete requests, identify the city by its exact name using the tools; never ask for or invent a database ID.
        For a create request without a population, call createCity with the population omitted so the tool can ask a follow-up question.
        Never invent a population. If a city name is missing or a name does not resolve uniquely, ask the user to clarify.
        """
    )
    fun chat(@MemoryId conversationId: String, @UserMessageAnnotation message: String): TokenStream
}

internal class CityAgentTools(
    private val conversations: AgentConversationRegistry,
    private val searchCities: (String) -> List<CityView>,
    private val enqueueChange: (CityCommand) -> Unit,
) {
    @Tool("Create a city. If population is not provided, ask the user for it before creating the city.")
    fun createCity(
        @P("The city's name") name: String,
        @P(value = "The city's population", defaultValue = "-1") population: Int,
        @ToolMemoryId conversationId: String,
    ): String {
        if (name.isBlank()) return "I need a city name before I can create a city."
        if (population < 0) {
            conversations.get(conversationId).pendingCreateName = name.trim()
            return "What is the population for \"${name.trim()}\"?"
        }
        return runCatching {
            enqueueChange(CityCommand(operation = "create", name = name.trim(), population = population))
            conversations.get(conversationId).pendingCreateName = null
            "Queued creation of ${name.trim()} with a population of $population."
        }.getOrElse { "I couldn't create ${name.trim()}: ${it.message ?: "the city update failed"}." }
    }

    @Tool("Update a city by its current exact name. Search for the city first; do not use or request a database ID.")
    fun updateCity(
        @P("The current exact name of the city to update") existingName: String,
        @P("The updated city name") name: String,
        @P("The updated population") population: Int,
    ): String {
        if (name.isBlank() || population < 0) return "A non-empty city name and a non-negative population are required."
        val lookup = lookupCity(existingName)
        val city = lookup.city ?: return lookup.failure!!
        return runCatching {
            enqueueChange(CityCommand("update", city.id, name.trim(), population))
            "Queued update of ${city.name} to ${name.trim()} with a population of $population."
        }.getOrElse { "I couldn't update ${city.name}: ${it.message ?: "the city update failed"}." }
    }

    @Tool("Delete a city by its exact name. Search for the city first; do not use or request a database ID.")
    fun deleteCity(@P("The exact name of the city to delete") name: String): String {
        val lookup = lookupCity(name)
        val city = lookup.city ?: return lookup.failure!!
        return runCatching {
            enqueueChange(CityCommand("delete", id = city.id))
            "Queued deletion of ${city.name}."
        }.getOrElse { "I couldn't delete ${city.name}: ${it.message ?: "the city update failed"}." }
    }

    fun completePendingCreate(conversationId: String, population: Int): String? {
        val conversation = conversations.get(conversationId)
        val name = conversation.pendingCreateName ?: return null
        if (population < 0) {
            return "I cannot complete creating $name without a non-negative whole-number population. What is its population?"
        }
        return runCatching {
            enqueueChange(CityCommand(operation = "create", name = name, population = population))
            conversation.pendingCreateName = null
            "Queued creation of $name with a population of $population."
        }.getOrElse { "I couldn't create $name: ${it.message ?: "the city update failed"}." }
    }

    private fun lookupCity(name: String): CityLookup {
        val normalizedName = name.trim()
        val exactMatches = runCatching {
            searchCities(normalizedName).filter { it.name.equals(normalizedName, ignoreCase = true) }
        }.getOrElse { return CityLookup(failure = "City search is unavailable, so I couldn't safely change $name.") }
        return when (exactMatches.size) {
            1 -> CityLookup(city = exactMatches.single())
            0 -> CityLookup(failure = "I couldn't find a city named $name.")
            else -> CityLookup(failure = "More than one city matches $name; please clarify which city you mean.")
        }
    }

    private data class CityLookup(val city: CityView? = null, val failure: String? = null)
}

class CityAgent(
    application: Application,
    baseUrl: String,
    modelName: String,
    apiKey: String,
    searchCities: (String) -> List<CityView>,
    enqueueChange: (CityCommand) -> Unit,
) {
    private val conversations = AgentConversationRegistry()
    private val tools = CityAgentTools(conversations, searchCities, enqueueChange)
    private val assistant = AiServices.builder(CityAssistant::class.java)
        .streamingChatModel(
            OpenAiStreamingChatModel.builder()
                .baseUrl(openAiBaseUrl(baseUrl))
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .build()
        )
        .chatMemoryProvider { memoryId -> conversations.get(memoryId.toString()).memory }
        .tools(tools)
        .build()
    private val log = application.log

    fun Route.routes() {
        post("/demo/agent/chat/stream") {
            val request = runCatching { call.receive<AgentChatRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, "Invalid chat request")
                return@post
            }
            if (request.conversationId.isBlank() || request.conversationId.length > 128 ||
                request.message.isBlank() || request.message.length > 4000
            ) {
                call.respond(HttpStatusCode.BadRequest, "A conversation ID and a message of at most 4000 characters are required")
                return@post
            }

            call.respondBytesWriter(ContentType.Text.EventStream) {
                val conversation = conversations.get(request.conversationId)
                conversation.lock.withLock {
                    val pendingName = conversation.pendingCreateName
                    val population = request.message.trim().replace(",", "").toIntOrNull()
                    val followUp = if (pendingName != null && population != null) {
                        tools.completePendingCreate(request.conversationId, population)
                    } else if (pendingName != null && request.message.trim().equals("cancel", ignoreCase = true)) {
                        conversation.pendingCreateName = null
                        "Cancelled creating $pendingName."
                    } else if (pendingName != null) {
                        "I cannot complete creating $pendingName without a whole-number population. What is its population?"
                    } else {
                        null
                    }

                    if (followUp != null) {
                        conversation.memory.add(UserMessage.from(request.message))
                        conversation.memory.add(AiMessage.from(followUp))
                        writeEvent(AgentStreamEvent("token", content = followUp))
                        writeEvent(AgentStreamEvent("done"))
                    } else {
                        val events = Channel<AgentStreamEvent>(Channel.UNLIMITED)
                        try {
                            assistant.chat(request.conversationId, request.message)
                                .onPartialResponse { partial -> events.trySend(AgentStreamEvent("token", content = partial)) }
                                .onCompleteResponse { events.trySend(AgentStreamEvent("done")); events.close() }
                                .onError { error ->
                                    log.error("LLM chat request failed", error)
                                    events.trySend(AgentStreamEvent("error", error = "The assistant is temporarily unavailable."))
                                    events.close()
                                }
                                .start()
                            for (event in events) writeEvent(event)
                        } catch (error: Throwable) {
                            log.error("Unable to start LLM chat stream", error)
                            writeEvent(AgentStreamEvent("error", error = "The assistant is temporarily unavailable."))
                        } finally {
                            events.cancel()
                        }
                    }
                }
            }
        }
    }

    private suspend fun ByteWriteChannel.writeEvent(event: AgentStreamEvent) {
        writeStringUtf8("data: ${Json.encodeToString(event)}\n\n")
        flush()
    }
}

private fun openAiBaseUrl(endpoint: String): String = endpoint.trimEnd('/')
    .removeSuffix("/chat/completions")
    .trimEnd('/')
