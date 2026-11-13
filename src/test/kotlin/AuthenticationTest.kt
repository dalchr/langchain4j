package com.example

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticationTest {
    @Test
    fun `disabled development login returns an actionable response`() = testApplication {
        application {
            configureSerialization()
            configureAuthenticationForTest(AuthenticationService(null), false, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        }

        val csrf = client.get("/auth/csrf")
        val token = Json.decodeFromString<CsrfTokenResponse>(csrf.bodyAsText()).token
        val csrfCookie = assertNotNull(csrf.headers[HttpHeaders.SetCookie]).substringBefore(';')
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, csrfCookie)
            header("X-CSRF-Token", token)
            setBody("""{"username":"chris","password":"chris"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("DEV_AUTH_ENABLED=true"))
    }

    @Test
    fun `login creates an encrypted session and logout invalidates it`() = testApplication {
        application {
            configureSerialization()
            configureAuthenticationForTest(testAuthenticationService(), false, ByteArray(32) { 1 }, ByteArray(32) { 2 })
            configureWebsockets()
            routing {
                authenticate("auth-session") {
                    get("/protected/create") {
                        if (call.requirePermission(Permission.CRUD_CREATE)) call.respondText("allowed")
                    }
                    post("/protected/mutate") {
                        if (!call.requirePermission(Permission.CRUD_CREATE) || !call.requireCsrfToken()) return@post
                        call.respondText("allowed")
                    }
                }
            }
        }

        val anonymous = client.get("/auth/me")
        assertEquals(HttpStatusCode.OK, anonymous.status)
        assertFalse(Json.decodeFromString<AuthenticationResponse>(anonymous.bodyAsText()).authenticated)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/protected/create").status)
        val csrf = client.get("/auth/csrf")
        val csrfToken = Json.decodeFromString<CsrfTokenResponse>(csrf.bodyAsText()).token
        val csrfCookieHeader = assertNotNull(csrf.headers[HttpHeaders.SetCookie])
        assertTrue(csrfCookieHeader.contains("SameSite=Lax", ignoreCase = true))
        assertFalse(csrfCookieHeader.contains("HttpOnly", ignoreCase = true))
        val csrfCookie = csrfCookieHeader.substringBefore(';')

        val invalidLogin = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, csrfCookie)
            header("X-CSRF-Token", csrfToken)
            setBody("""{"username":"chris","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, invalidLogin.status)

        val missingCsrf = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"chris","password":"chris"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)

        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, csrfCookie)
            header("X-CSRF-Token", csrfToken)
            setBody("""{"username":"chris","password":"chris"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val cookieHeader = assertNotNull(login.headers[HttpHeaders.SetCookie])
        assertTrue(cookieHeader.contains("HttpOnly", ignoreCase = true))
        assertTrue(cookieHeader.contains("SameSite=lax", ignoreCase = true))
        assertFalse(cookieHeader.contains("local:chris", ignoreCase = true))
        assertFalse(cookieHeader.contains("chris", ignoreCase = true))
        var cookie = cookieHeader.substringBefore(';')

        val secondLogin = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, "$cookie; $csrfCookie")
            header("X-CSRF-Token", csrfToken)
            setBody("""{"username":"chris","password":"chris"}""")
        }
        val secondCookie = assertNotNull(secondLogin.headers[HttpHeaders.SetCookie]).substringBefore(';')
        assertNotEquals(cookie, secondCookie)
        assertFalse(Json.decodeFromString<AuthenticationResponse>(client.get("/auth/me") {
            header(HttpHeaders.Cookie, cookie)
        }.bodyAsText()).authenticated)
        cookie = secondCookie

        val currentUser = client.get("/auth/me") { header(HttpHeaders.Cookie, cookie) }
        val authenticated = Json.decodeFromString<AuthenticationResponse>(currentUser.bodyAsText())
        assertTrue(authenticated.authenticated)
        assertEquals("Chris", authenticated.user?.displayName)
        assertEquals("local:chris", authenticated.user?.id)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/protected/create") { header(HttpHeaders.Cookie, cookie) }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/protected/mutate") { header(HttpHeaders.Cookie, "$cookie; $csrfCookie") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/protected/mutate") {
                header(HttpHeaders.Cookie, "$cookie; $csrfCookie")
                header("X-CSRF-Token", csrfToken)
            }.status,
        )

        val logout = client.post("/auth/logout") {
            header(HttpHeaders.Cookie, "$cookie; $csrfCookie")
            header("X-CSRF-Token", csrfToken)
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        val afterLogout = client.get("/auth/status") { header(HttpHeaders.Cookie, cookie) }
        assertFalse(Json.decodeFromString<AuthenticationResponse>(afterLogout.bodyAsText()).authenticated)
    }

    @Test
    fun `production cannot enable development authentication`() {
        val error = runCatching {
            AuthenticationService.fromEnvironment(
                mapOf(
                    "APP_ENV" to "production",
                    "DEV_AUTH_ENABLED" to "true",
                    "DEV_AUTH_USERNAME" to "chris",
                    "DEV_AUTH_PASSWORD" to "chris",
                ),
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error.message.orEmpty().contains("cannot be enabled in production"))

        val missingEnvironmentError = runCatching {
            AuthenticationService.fromEnvironment(
                mapOf("DEV_AUTH_ENABLED" to "true", "DEV_AUTH_USERNAME" to "chris", "DEV_AUTH_PASSWORD" to "chris"),
            )
        }.exceptionOrNull()
        assertTrue(missingEnvironmentError?.message.orEmpty().contains("APP_ENV=development"))
    }

    @Test
    fun `server-side session expires after its lifetime`() {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val service = testAuthenticationService(clock)
        val login = service.login("chris", "chris")
        assertTrue(login is LoginResult.Authenticated)
        val session = login.session

        assertNotNull(service.resolve(session))
        clock.current = clock.current.plus(Duration.ofHours(8)).plusSeconds(1)
        assertNull(service.resolve(session))
    }

    @Test
    fun `failed login attempts are rate limited by client`() {
        val service = testAuthenticationService()

        repeat(5) {
            assertEquals(LoginResult.InvalidCredentials, service.login("chris", "wrong", "client-1"))
        }
        assertEquals(LoginResult.RateLimited, service.login("chris", "chris", "client-1"))
        assertTrue(service.login("chris", "chris", "client-2") is LoginResult.Authenticated)
    }

    @Test
    fun `secure deployment mode sets secure cookies`() = testApplication {
        application {
            configureSerialization()
            configureAuthenticationForTest(testAuthenticationService(), true, ByteArray(32) { 5 }, ByteArray(32) { 6 })
        }

        val csrf = client.get("/auth/csrf")
        assertTrue(assertNotNull(csrf.headers[HttpHeaders.SetCookie]).contains("Secure", ignoreCase = true))
        val token = Json.decodeFromString<CsrfTokenResponse>(csrf.bodyAsText()).token
        val csrfCookie = assertNotNull(csrf.headers[HttpHeaders.SetCookie]).substringBefore(';')
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, csrfCookie)
            header("X-CSRF-Token", token)
            setBody("""{"username":"chris","password":"chris"}""")
        }
        assertTrue(assertNotNull(login.headers[HttpHeaders.SetCookie]).contains("Secure", ignoreCase = true))
    }

    @Test
    fun `authenticated users without the required role are forbidden`() = testApplication {
        application {
            configureSerialization()
            configureAuthenticationForTest(
                AuthenticationService(
                    LocalAccount(
                        AuthenticatedUser("local:reader", "reader", "Reader", roles = listOf("READER")),
                        BCrypt.hashpw("secret", BCrypt.gensalt(4)),
                    ),
                ),
                false,
                ByteArray(32) { 3 },
                ByteArray(32) { 4 },
            )
            routing {
                authenticate("auth-session") {
                    get("/protected/create") {
                        if (call.requirePermission(Permission.CRUD_CREATE)) call.respondText("allowed")
                    }
                }
            }
        }

        val csrf = client.get("/auth/csrf")
        val token = Json.decodeFromString<CsrfTokenResponse>(csrf.bodyAsText()).token
        val csrfCookie = assertNotNull(csrf.headers[HttpHeaders.SetCookie]).substringBefore(';')
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, csrfCookie)
            header("X-CSRF-Token", token)
            setBody("""{"username":"reader","password":"secret"}""")
        }
        val sessionCookie = assertNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')

        val protectedResponse = client.get("/protected/create") { header(HttpHeaders.Cookie, sessionCookie) }
        assertEquals(HttpStatusCode.Forbidden, protectedResponse.status)
        val user = Json.decodeFromString<AuthenticationResponse>(client.get("/auth/me") {
            header(HttpHeaders.Cookie, sessionCookie)
        }.bodyAsText()).user
        assertFalse(AuthorizationService.hasPermission(assertNotNull(user), Permission.CRUD_CREATE))
        assertFalse(user.permissions.contains(Permission.CRUD_CREATE.name))
    }

    private fun testAuthenticationService(clock: Clock = Clock.systemUTC()) = AuthenticationService(
        LocalAccount(
            user = AuthenticatedUser("local:chris", "chris", "Chris"),
            passwordHash = BCrypt.hashpw("chris", BCrypt.gensalt(4)),
        ),
        clock,
    )

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }
}