package com.example

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `anonymous users can read cities but cannot mutate them`() = testApplication {
        application {
            configureSerialization()
            configureAuthenticationForTest(AuthenticationService(null), false, ByteArray(32) { 1 }, ByteArray(32) { 2 })
            routing {
                cityReadRoutes(
                    listCities = { listOf(CityView(1, "Oslo", 709037)) },
                    searchCities = { name -> listOf(CityView(1, name, 709037)) },
                )
                authenticate("auth-session") {
                    post("/demo/cities") {
                        if (!call.requirePermission(Permission.CRUD_CREATE) || !call.requireCsrfToken()) return@post
                        call.respondText("queued")
                    }
                    put("/demo/cities/{id}") {
                        if (!call.requirePermission(Permission.CRUD_UPDATE) || !call.requireCsrfToken()) return@put
                        call.respondText("queued")
                    }
                    delete("/demo/cities/{id}") {
                        if (!call.requirePermission(Permission.CRUD_DELETE) || !call.requireCsrfToken()) return@delete
                        call.respondText("queued")
                    }
                }
            }
        }

        assertEquals(HttpStatusCode.OK, client.get("/demo/cities").status)
        assertEquals(HttpStatusCode.OK, client.get("/demo/cities/search?name=Oslo").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/demo/cities") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.put("/demo/cities/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/demo/cities/1").status)
    }

    @Test
    fun `test root endpoint`() = testApplication {
        // loads default configuration
        configure()
        // verify server root returns 200
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

}
