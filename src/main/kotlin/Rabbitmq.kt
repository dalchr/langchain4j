package com.example

import io.github.damir.denis.tudor.ktor.server.rabbitmq.RabbitMQ
import io.github.damir.denis.tudor.ktor.server.rabbitmq.ConnectionManagersKey
import io.github.damir.denis.tudor.ktor.server.rabbitmq.dsl.*
import io.github.damir.denis.tudor.ktor.server.rabbitmq.rabbitMQ
import io.ktor.server.application.*
import io.ktor.server.config.property
import io.ktor.server.config.propertyOrNull
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.UUID

fun Application.configureRabbitmq() {
    val consumerQueue = "test-queue-${UUID.randomUUID()}"
    val connectionUri: String = propertyOrNull("rabbitmq.uri") ?: run {
        log.info("RabbitMQ disabled, no connection URI provided")
        return@configureRabbitmq
    }
    val connectionName: String = property("rabbitmq.name")
    val exceptionHandler =
        CoroutineExceptionHandler { _, throwable -> log.error("ExceptionHandler got $throwable") }
    val rabbitMQScope = CoroutineScope(SupervisorJob() + exceptionHandler)

    install(RabbitMQ) {
        uri = connectionUri
        defaultConnectionName = connectionName
        dispatcherThreadPollSize = 4
        tlsEnabled = false
        scope = rabbitMQScope // custom scope, default is the one provided by Ktor
    }
    val connectionManager = attributes[ConnectionManagersKey].values.first()
    CityCommandPublisher.configure(connectionManager, connectionName)

    rabbitmq {
        queueBind {
            queue = "dlq"
            exchange = "dlx"
            routingKey = "dlq-dlx"
            exchangeDeclare {
                exchange = "dlx"
                type = "direct"
            }
            queueDeclare {
                queue = "dlq"
                durable = true
            }
        }
    }

    rabbitmq {
        queueBind {
            queue = consumerQueue
            exchange = "test-exchange"
            routingKey = "test-routing-key"
            exchangeDeclare {
                exchange = "test-exchange"
                type = "direct"
            }
            queueDeclare {
                queue = consumerQueue
                arguments = mapOf(
                    "x-dead-letter-exchange" to "dlx",
                    "x-dead-letter-routing-key" to "dlq-dlx"
                )
            }
        }
    }

    routing {
        rabbitmq {
            get("/rabbitmq") {
                basicPublish {
                    exchange = "test-exchange"
                    routingKey = "test-routing-key"
                    properties = basicProperties {
                        correlationId = "jetbrains"
                        type = "plugin"
                        headers = mapOf("ktor" to "rabbitmq")
                    }
                    message { "Hello Ktor!" }
                }

                call.respondText("Hello RabbitMQ!")
            }
        }

        rabbitmq {
            basicConsume {
                autoAck = true
                queue = consumerQueue
                dispatcher = Dispatchers.rabbitMQ
                coroutinePollSize = 100

                // If an exception is not properly handled in your business logic,
                // it will be caught by the default Ktor coroutine scope.
                // By defining your own coroutine scope, you gain more flexibility in handling exceptions.
                deliverCallback<String> { message ->
                    val body = message.body
                    log.info("RabbitMQ consumer received: $body")
                    ConsumerState.consumedMessages.add(body)
                    try {
                        val normalizedBody = if (body.startsWith("\"")) {
                            Json.decodeFromString<String>(body)
                        } else {
                            body
                        }
                        if (normalizedBody.startsWith("CITY_CHANGE:")) {
                            runBlocking(Dispatchers.IO) {
                                CityChangeProcessor.apply(
                                    Json.decodeFromString(normalizedBody.removePrefix("CITY_CHANGE:"))
                                )
                            }
                            log.info("RabbitMQ city change persisted successfully")
                        } else {
                            ConsumerState.onMessage(body)
                        }
                    } catch (error: Throwable) {
                        log.error("Failed to persist RabbitMQ message: $body", error)
                    }
                }

                // Define a callback to handle deserialization failures.
                // For example, you could redirect such messages to a dead-letter queue.
                deliverFailureCallback { message ->
                    log.info(
                        "Received undeliverable message (deserialization failed): ${
                            message.body.toString(
                                Charsets.UTF_8
                            )
                        }"
                    )
                }
            }
        }
    }
}
