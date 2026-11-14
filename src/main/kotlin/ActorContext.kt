package com.example

import kotlinx.serialization.Serializable

/**
 * Wraps context information about the actor performing an operation.
 * Keeps actorId, actorName, sessionId, source and conversationId together
 * so they are always passed/used as a single unit.
 */
@Serializable
data class ActorContext(
    val conversationId: String? = null,
    val actorId: String? = null,
    val actorName: String? = null,
    val sessionId: String? = null,
    val source: String? = null,
)
