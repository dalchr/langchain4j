package com.example

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable

@Serializable
data class WsMessage(val body: String)

@Serializable
data class CityChangedMessage(val event: String = "city-changed")

object ConsumerState {
    val consumedMessages = mutableListOf<String>()
    private val messageChannel = Channel<WsMessage>(Channel.UNLIMITED)
    
    val messages: Channel<WsMessage> get() = messageChannel
    
    fun onMessage(body: String) {
        consumedMessages.add(body)
        messageChannel.trySend(WsMessage(body))
    }
}
