package com.example

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.databind.JsonNode
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolMemoryId
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.MemoryId
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler
import dev.langchain4j.service.tool.ToolExecutionErrorHandler
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import dev.langchain4j.service.UserMessage as UserMessageAnnotation

@Serializable
data class AgentChatRequest(val conversationId: String, val message: String)

@Serializable
data class AgentStreamEvent(val type: String, val content: String? = null, val error: String? = null)

internal data class AgentConversation(
    val memory: ChatMemory,
    val lock: Mutex = Mutex(),
    var pendingCreateName: String? = null,
    val activityHistory: MutableList<AgentHistoryEntry> = mutableListOf(),
)

internal data class AgentHistoryEntry(
    val role: String,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val actorId: String? = null,
)

internal fun agentConversationKey(user: AuthenticatedUser, conversationId: String): String =
    "${user.id.length}:${user.id}${conversationId.length}:$conversationId"

internal fun recoverFromUnknownCityTool(request: ToolExecutionRequest): ToolExecutionResultMessage =
    ToolExecutionResultMessage.toolExecutionResultMessage(
        request,
        "The tool name '${request.name()}' is not registered. Use only these exact tool names: " +
            "listCities, lookupCityByName, operationHistory, createCity, updateCity, deleteCity, updateCities. " +
            "Retry the requested action using the appropriate exact tool name.",
    )

internal fun populationFromReply(message: String): Int? {
    val values = Regex("(?<![\\p{L}\\p{N}])-?\\d[\\d,]*(?![\\p{L}\\p{N}])")
        .findAll(message)
        .map { it.value.replace(",", "").toIntOrNull() }
        .toList()
    if (values.size != 1) return null
    return values.single()?.takeIf { it >= 0 }
}


@Serializable
internal data class CityPopulationUpdate(
    val name: String,
    val newPopulation: Int,
) {
    companion object {
        @JvmStatic
        @JsonCreator
        fun fromJson(node: JsonNode): CityPopulationUpdate =
            CityPopulationUpdate(
                name = node.get("name").asText(),
                newPopulation = node.get("newPopulation").asInt(),
            )
    }
}

internal class AgentConversationRegistry(
    private val maxMessages: Int = 20,
) {
    private val conversations = ConcurrentHashMap<String, AgentConversation>()

    fun get(id: String): AgentConversation = conversations.computeIfAbsent(id) {
        AgentConversation(MessageWindowChatMemory.builder().id(it).maxMessages(maxMessages).build())
    }

    suspend fun reset(id: String) {
        val conversation = get(id)
        conversation.lock.withLock {
            conversation.memory.clear()
            conversation.pendingCreateName = null
            conversation.activityHistory.clear()
        }
    }
}

private interface CityAssistant {
    @SystemMessage(
        """
        You help manage the cities in this application. Whenever the user asks about cities or discusses a city, use the city tools to ground your answer in current registered data. For general city questions, list all known cities and populations. For a specific city, look it up by name and state whether it exists and its current registered population.
        When asked about past actions or accidental changes, consult the operation history, which includes this conversation's queued assistant actions and committed changes made through the dashboard. Treat queued assistant operations as requests, not proof that asynchronous database persistence succeeded.
        Use the available city tools for every requested change. Only perform a city mutation when the authenticated user directly requests it.
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
    private val listCities: () -> List<CityView>,
    private val enqueueChange: (CityCommand) -> Unit,
) {
    private val executionContexts = ConcurrentHashMap<String, AuthenticatedUser>()

    fun bindExecutionContext(conversationId: String, user: AuthenticatedUser): AutoCloseable {
        check(executionContexts.putIfAbsent(conversationId, user) == null) { "An execution context is already active" }
        return AutoCloseable { executionContexts.remove(conversationId, user) }
    }

    @Tool("List all cities currently registered and their populations. Use this for general questions about the cities.")
    fun listCities(@ToolMemoryId conversationId: String): String {
        authorizationFailure(conversationId, Permission.CRUD_READ)?.let { return it }
        return runCatching {
            listCities.invoke().joinToString("\n") { "${it.name}: ${it.population}" }
                .ifEmpty { "No cities are currently registered." }
        }.getOrElse { "City data is currently unavailable." }
    }

    @Tool("Look up a specific city by name and report whether it exists and its current registered population.")
    fun lookupCityByName(
        @P(name = "name", value = "The city name to look up") name: String,
        @ToolMemoryId conversationId: String,
    ): String {
        authorizationFailure(conversationId, Permission.CRUD_READ)?.let { return it }
        val lookup = lookupCity(name)
        val city = lookup.city ?: return lookup.failure ?: "No city found with name '$name'."
        return "${city.name} exists and its current registered population is ${city.population}."
    }
    @Tool("Create a city. If population is not provided, ask the user for it before creating the city.")
    fun createCity(
        @P("The city's name") name: String,
        @P(value = "The city's population", defaultValue = "-1") population: Int,
        @ToolMemoryId conversationId: String,
    ): String {
        authorizationFailure(conversationId, Permission.CRUD_CREATE)?.let { return it }
        if (name.isBlank()) return "I need a city name before I can create a city."
        if (population < 0) {
            conversations.get(conversationId).pendingCreateName = name.trim()
            return "What is the population for \"${name.trim()}\"?"
        }
        return runCatching {
            val actor = executionContexts.getValue(conversationId)
            enqueueChange(
                CityCommand(
                    operation = "create",
                    name = name.trim(),
                    population = population,
                    conversationId = conversationId,
                    actorId = actor.id,
                ),
            )
            recordOperation(conversationId, "Created ${name.trim()} with population $population", actor.id)
            conversations.get(conversationId).pendingCreateName = null
            "Queued creation of ${name.trim()} with a population of $population."
        }.getOrElse { "I couldn't create ${name.trim()}: ${it.message ?: "the city update failed"}." }
    }

    @Tool("Update a city by its current exact name. If multiple cities share the name, use the population to identify which one.")
    fun updateCity(
        @P("The current exact name of the city to update") existingName: String,
        @P("The updated city name") name: String,
        @P("The updated population") population: Int,
        @P(value = "The current population of the city to identify it when multiple cities share the same name", defaultValue = "-1") disambiguatingPopulation: Int,
        @ToolMemoryId conversationId: String,
    ): String {
        authorizationFailure(conversationId, Permission.CRUD_UPDATE)?.let { return it }
        if (name.isBlank() || population < 0) return "A non-empty city name and a non-negative population are required."
        val lookup = lookupCity(existingName)
        val city = when {
            lookup.city != null -> lookup.city
            lookup.multipleMatches != null -> {
                if (disambiguatingPopulation >= 0) {
                    lookup.multipleMatches.firstOrNull { it.population == disambiguatingPopulation }
                } else {
                    val list = lookup.multipleMatches.joinToString("; ") { "${it.name} (population: ${it.population})" }
                    return "Multiple cities named '$existingName' exist. Specify one by population, e.g.: $list"
                }
            }
            lookup.failure != null -> return lookup.failure
            else -> return "Unknown error looking up city $existingName."
        }
        if (city == null) return "No city found with name '$existingName' and population $disambiguatingPopulation."
        return runCatching {
            val actor = executionContexts.getValue(conversationId)
            enqueueChange(
                CityCommand(
                    operation = "update",
                    id = city.id,
                    name = name.trim(),
                    population = population,
                    conversationId = conversationId,
                    actorId = actor.id,
                ),
            )
            recordOperation(
                conversationId,
                "Updated ${city.name} to ${name.trim()}: population ${city.population} → $population",
                actor.id,
            )
            "Queued update of ${city.name} to ${name.trim()} with a population of $population."
        }.getOrElse { "I couldn't update ${city.name}: ${it.message ?: "the city update failed"}." }
    }

    @Tool("Delete a city by its exact name. If multiple cities share the name, you may use the population to specify which one.")
    fun deleteCity(
        @P("The exact name of the city to delete") name: String,
        @P(value = "The population to disambiguate when multiple cities share the same name", defaultValue = "-1") population: Int,
        @ToolMemoryId conversationId: String,
    ): String {
        authorizationFailure(conversationId, Permission.CRUD_DELETE)?.let { return it }
        val lookup = lookupCity(name)
        val city = when {
            lookup.city != null -> lookup.city
            lookup.multipleMatches != null -> {
                if (population >= 0) {
                    lookup.multipleMatches.firstOrNull { it.population == population }
                } else {
                    val list = lookup.multipleMatches.joinToString("; ") { "${it.name} (population: ${it.population})" }
                    return "Multiple cities named '$name' exist. Specify one by population, e.g.: $list"
                }
            }
            lookup.failure != null -> return lookup.failure
            else -> return "Unknown error looking up city $name."
        }
        if (city == null) return "No city found with name '$name' and population $population."
        return runCatching {
            val actor = executionContexts.getValue(conversationId)
            enqueueChange(CityCommand("delete", id = city.id, conversationId = conversationId, actorId = actor.id))
            recordOperation(conversationId, "Deleted ${city.name} with population ${city.population}", actor.id)
            "Queued deletion of ${city.name}."
        }.getOrElse { "I couldn't delete ${city.name}: ${it.message ?: "the city update failed"}." }
    }

    @Tool("Bulk update the populations of multiple cities in a single call. This reduces the number of LLM roundtrips needed for updating several cities at once. Each update specifies a city name and a new population.")
    fun updateCities(
        @P(value = "A list of cities to update, each with the city name and new population") updates: List<CityPopulationUpdate>,
        @ToolMemoryId conversationId: String,
    ): String {
        authorizationFailure(conversationId, Permission.CRUD_UPDATE)?.let { return it }
        val results = mutableListOf<String>()
        var successCount = 0

        for (update in updates) {
            val name = update.name.trim()
            val newPopulation = update.newPopulation

            if (name.isBlank()) {
                results.add("Skipped empty city name.")
                continue
            }

            val lookup = lookupCity(name)
            val resolution = when {
                lookup.city != null -> CityResolved(lookup.city)
                lookup.multipleMatches != null -> {
                    val list = lookup.multipleMatches.joinToString("; ") { "${it.name} (population: ${it.population})" }
                    CityNotFound("${name}: Multiple cities exist with that name. Specify one: $list")
                }
                lookup.failure != null -> CityNotFound("${name}: ${lookup.failure}")
                else -> CityNotFound("${name}: Unknown error looking up city.")
            }

            when (resolution) {
                is CityResolved -> runCatching {
                    val actor = executionContexts.getValue(conversationId)
                    enqueueChange(
                        CityCommand(
                            operation = "update",
                            id = resolution.city.id,
                            name = resolution.city.name,
                            population = newPopulation,
                            conversationId = conversationId,
                            actorId = actor.id,
                        ),
                    )
                    recordOperation(conversationId, "Updated ${resolution.city.name} to population ${resolution.city.population} → $newPopulation", actor.id)
                    successCount++
                    results.add("${resolution.city.name}: Updated population to $newPopulation.")
                }.getOrElse {
                    results.add("${resolution.city.name}: Update failed - ${it.message ?: "unknown error"}")
                }
                is CityNotFound -> results.add(resolution.message)
            }
        }

        val cityStr = if (successCount == 1) "city" else "cities"
        val summary = "Updated $successCount $cityStr. ${results.joinToString(" ")}"
        return summary
    }

    fun completePendingCreate(conversationId: String, population: Int): String? {
        authorizationFailure(conversationId, Permission.CRUD_CREATE)?.let { return it }
        val conversation = conversations.get(conversationId)
        val name = conversation.pendingCreateName ?: return null
        if (population < 0) {
            return "I cannot complete creating $name without a non-negative whole-number population. What is its population?"
        }
        return runCatching {
            val actor = executionContexts.getValue(conversationId)
            enqueueChange(
                CityCommand(
                    operation = "create",
                    name = name,
                    population = population,
                    conversationId = conversationId,
                    actorId = actor.id,
                ),
            )
            recordOperation(conversationId, "Created $name with population $population", actor.id)
            conversation.pendingCreateName = null
            "Queued creation of $name with a population of $population."
        }.getOrElse { "I couldn't create $name: ${it.message ?: "the city update failed"}." }
    }

    @Tool("Review this conversation's messages and city operations, including committed changes made through the dashboard. Use this for questions about what was previously created, updated, or deleted.")
    fun operationHistory(@ToolMemoryId conversationId: String): String {
        authorizationFailure(conversationId, Permission.CHAT_ACCESS)?.let { return it }
        val history = conversations.get(conversationId).activityHistory
        val entries = (history.filter { it.role != "city_operation" } +
            CityOperationHistory.snapshot()
                .filter { it.conversationId == null }
                .map { AgentHistoryEntry("external_city_operation", it.description, it.timestamp, it.actorId) } +
            history.filter { it.role == "city_operation" })
            .sortedBy { it.timestamp }
        if (entries.isEmpty()) return "No city changes have been queued in this conversation."
        return entries.mapIndexed { index, operation ->
            val label = when (operation.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                "city_operation" -> "City operation (queued)"
                "external_city_operation" -> "City operation (committed outside the assistant)"
                else -> "City operation (queued)"
            }
            "${index + 1}. $label: ${operation.content}"
        }.joinToString("\n")
    }

    fun recordMessage(conversationId: String, role: String, content: String, actorId: String? = null) {
        val history = conversations.get(conversationId).activityHistory
        if (history.size == MAX_CONVERSATION_HISTORY) history.removeAt(0)
        history.add(AgentHistoryEntry(role, content, actorId = actorId))
    }

    private fun recordOperation(conversationId: String, description: String, actorId: String) {
        recordMessage(conversationId, "city_operation", description, actorId)
    }

    private fun authorizationFailure(conversationId: String, permission: Permission): String? {
        val user = executionContexts[conversationId]
            ?: return "AUTHENTICATION_REQUIRED: Please log in before accessing city assistant tools."
        return if (AuthorizationService.hasPermission(user, permission)) {
            null
        } else {
            println("PERMISSION_DENIED: You are not allowed to perform this operation.")
            "PERMISSION_DENIED: You are not allowed to perform this operation."
        }
    }

    private fun lookupCity(name: String): CityLookup {
        val normalizedName = name.normalizeCityName()
        val exactMatches = runCatching {
            searchCities(normalizedName).filter { it.name.normalizeCityName() == normalizedName }
        }.getOrElse { return CityLookup(failure = "City search is unavailable, so I couldn't safely change $name.") }
        return when (exactMatches.size) {
            1 -> CityLookup(city = exactMatches.single())
            0 -> CityLookup(failure = "I couldn't find a city named $name.")
            else -> CityLookup(multipleMatches = exactMatches)
        }
    }

    private fun String.normalizeCityName(): String =
        replace(Regex("\\p{Space}"), " ").trim()

    private data class CityLookup(
        val city: CityView? = null,
        val failure: String? = null,
        val multipleMatches: List<CityView>? = null,
    )

    private sealed class CityLookupResult
    private data class CityResolved(val city: CityView) : CityLookupResult()
    private data class CityNotFound(val message: String) : CityLookupResult()


    private companion object {
        const val MAX_CONVERSATION_HISTORY = 100
    }
}

class CityAgent(
    application: Application,
    baseUrl: String,
    modelName: String,
    apiKey: String,
    searchCities: (String) -> List<CityView>,
    listCities: () -> List<CityView>,
    enqueueChange: (CityCommand) -> Unit,
) {
    private val conversations = AgentConversationRegistry()
    private val tools = CityAgentTools(conversations, searchCities, listCities, enqueueChange)
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
        .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
        .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
        .hallucinatedToolNameStrategy(::recoverFromUnknownCityTool)
        .build()
    private val log = application.log

    fun Route.routes() {
        post("/demo/agent/conversation/reset") {
            if (!call.requirePermission(Permission.CHAT_ACCESS) || !call.requireCsrfToken()) return@post
            val request = runCatching { call.receive<AgentResetRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, "Invalid conversation reset request")
                return@post
            }
            if (request.conversationId.isBlank() || request.conversationId.length > 128) {
                call.respond(HttpStatusCode.BadRequest, "A valid conversation ID is required")
                return@post
            }
            val user = call.currentAuthenticatedUser() ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            conversations.reset(agentConversationKey(user, request.conversationId))
            call.respond(HttpStatusCode.OK, "Conversation restarted")
        }

        post("/demo/agent/chat/stream") {
            if (!call.requirePermission(Permission.CHAT_ACCESS) || !call.requireCsrfToken()) return@post
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
            val user = call.currentAuthenticatedUser() ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val conversationId = agentConversationKey(user, request.conversationId)

            call.respondBytesWriter(ContentType.Text.EventStream) {
                val conversation = conversations.get(conversationId)
                conversation.lock.withLock {
                    val executionContext = tools.bindExecutionContext(conversationId, user)
                    try {
                        val requestStartedAt = System.nanoTime()
                        log.info("Starting LLM chat request for conversation {}", request.conversationId)
                        tools.recordMessage(conversationId, "user", request.message, user.id)
                        val pendingName = conversation.pendingCreateName
                        val population = populationFromReply(request.message)
                        val followUp = if (pendingName != null && population != null) {
                            tools.completePendingCreate(conversationId, population)
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
                            tools.recordMessage(conversationId, "assistant", followUp, user.id)
                            writeEvent(AgentStreamEvent("token", content = followUp))
                            writeEvent(AgentStreamEvent("done"))
                            log.info(
                                "LLM chat completed through pending-create follow-up for conversation {} ({} ms, {} characters)",
                                request.conversationId,
                                (System.nanoTime() - requestStartedAt) / 1_000_000,
                                followUp.length,
                            )
                        } else {
                            val events = Channel<AgentStreamEvent>(Channel.UNLIMITED)
                            val responseText = StringBuilder()
                            try {
                                assistant.chat(conversationId, request.message)
                                    .onPartialResponse { partial ->
                                        responseText.append(partial)
                                        events.trySend(AgentStreamEvent("token", content = partial))
                                    }
                                    .onCompleteResponse {
                                        val response = responseText.toString()
                                        if (response.isBlank()) {
                                            log.warn("LLM chat completed without text for conversation {} ({} ms)", request.conversationId, (System.nanoTime() - requestStartedAt) / 1_000_000)
                                            events.trySend(AgentStreamEvent("error", error = "The assistant returned an empty response. Please try again."))
                                        } else {
                                            tools.recordMessage(conversationId, "assistant", response, user.id)
                                            log.info("LLM chat completed for conversation {} ({} ms, {} characters)", request.conversationId, (System.nanoTime() - requestStartedAt) / 1_000_000, response.length)
                                            events.trySend(AgentStreamEvent("done"))
                                        }
                                        events.close()
                                    }
                                    .onError { error ->
                                        log.error("LLM chat request failed for conversation {} after {} ms", request.conversationId, (System.nanoTime() - requestStartedAt) / 1_000_000, error)
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
                    } finally {
                        executionContext.close()
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

@Serializable
data class AgentResetRequest(val conversationId: String)

private fun openAiBaseUrl(endpoint: String): String = endpoint.trimEnd('/')
    .removeSuffix("/chat/completions")
    .trimEnd('/')
