package com.example

import org.mindrot.jbcrypt.BCrypt
import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.session
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.set
import io.ktor.server.sessions.sessions
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val AUTH_SESSION_COOKIE = "ktor-auth-session"
private const val CSRF_COOKIE = "XSRF-TOKEN"
private const val CSRF_HEADER = "X-CSRF-Token"
private const val MAX_LOGIN_CLIENTS = 10_000
private val AUTH_SESSION_LIFETIME: Duration = Duration.ofHours(8)
private val AUTHENTICATION_SERVICE_ATTRIBUTE = AttributeKey<AuthenticationService>("authentication-service")

@Serializable
data class AuthSession(val id: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class AuthenticatedUser(
    val id: String,
    val username: String,
    val displayName: String,
    val roles: List<String> = listOf("USER"),
    val permissions: List<String> = AuthorizationService.permissionsFor(roles).map(Permission::name),
)

@Serializable
data class AuthenticationResponse(val authenticated: Boolean, val user: AuthenticatedUser?)

@Serializable
data class CsrfTokenResponse(val token: String)

@Serializable
data class AuthenticationErrorResponse(val message: String)

enum class Permission {
    CRUD_CREATE,
    CRUD_READ,
    CRUD_UPDATE,
    CRUD_DELETE,
    CHAT_ACCESS,
}

object AuthorizationService {
    private val permissionsByRole = mapOf("USER" to Permission.entries.toSet())

    fun hasPermission(user: AuthenticatedUser, permission: Permission): Boolean =
        user.roles.any { role -> permission in permissionsByRole[role].orEmpty() }

    fun permissionsFor(roles: List<String>): List<Permission> =
        roles.flatMap { role -> permissionsByRole[role].orEmpty() }.distinct()
}

internal data class LocalAccount(
    val user: AuthenticatedUser,
    val passwordHash: String,
)

internal sealed interface LoginResult {
    data class Authenticated(val session: AuthSession, val user: AuthenticatedUser) : LoginResult
    data object Disabled : LoginResult
    data object InvalidCredentials : LoginResult
    data object RateLimited : LoginResult
}

internal class AuthenticatedPrincipal(val user: AuthenticatedUser)

internal class AuthenticationService(
    private val localAccount: LocalAccount?,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private data class SessionRecord(val user: AuthenticatedUser, val expiresAt: Instant)
    private data class FailedLoginState(
        val windowStartedAt: Instant,
        val attempts: Int,
        val blockedUntil: Instant?,
    )

    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val failedLogins = ConcurrentHashMap<String, FailedLoginState>()

    fun login(username: String, password: String, rateLimitKey: String = "test-client"): LoginResult {
        val account = localAccount ?: return LoginResult.Disabled
        val key = rateLimitKey.ifBlank { "unknown-client" }
        val now = clock.instant()
        if (failedLogins.size >= MAX_LOGIN_CLIENTS) {
            failedLogins.entries.removeIf { (_, state) ->
                val lockHasExpired = state.blockedUntil?.isAfter(now) != true
                lockHasExpired && !state.windowStartedAt.plus(LOGIN_FAILURE_WINDOW).isAfter(now)
            }
            if (failedLogins.size >= MAX_LOGIN_CLIENTS && !failedLogins.containsKey(key)) return LoginResult.RateLimited
        }
        val previousFailures = failedLogins[key]
        if (previousFailures?.blockedUntil?.isAfter(now) == true) return LoginResult.RateLimited
        if (previousFailures != null && !previousFailures.windowStartedAt.plus(LOGIN_FAILURE_WINDOW).isAfter(now)) {
            failedLogins.remove(key, previousFailures)
        }

        val passwordMatches = BCrypt.checkpw(password, account.passwordHash)
        if (username != account.user.username || !passwordMatches) {
            failedLogins.compute(key) { _, failures ->
                val activeFailures = failures?.takeIf { it.windowStartedAt.plus(LOGIN_FAILURE_WINDOW).isAfter(now) }
                val attempts = (activeFailures?.attempts ?: 0) + 1
                FailedLoginState(
                    windowStartedAt = activeFailures?.windowStartedAt ?: now,
                    attempts = attempts,
                    blockedUntil = if (attempts >= MAX_LOGIN_FAILURES) now.plus(LOGIN_LOCKOUT_DURATION) else null,
                )
            }
            return LoginResult.InvalidCredentials
        }
        failedLogins.remove(key)
        val sessionId = ByteArray(32).also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        sessions[sessionId] = SessionRecord(account.user, now.plus(AUTH_SESSION_LIFETIME))
        return LoginResult.Authenticated(AuthSession(sessionId), account.user)
    }

    fun resolve(session: AuthSession): AuthenticatedUser? {
        val record = sessions[session.id] ?: return null
        if (!record.expiresAt.isAfter(clock.instant())) {
            sessions.remove(session.id, record)
            return null
        }
        return record.user
    }

    fun logout(session: AuthSession?) {
        if (session != null) sessions.remove(session.id)
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AuthenticationService {
            val isDevelopment = environment["APP_ENV"].equals("development", ignoreCase = true)
            val isProduction = environment["APP_ENV"].equals("production", ignoreCase = true)
            val enabled = environment["DEV_AUTH_ENABLED"].equals("true", ignoreCase = true)
            check(!isProduction || !enabled) { "Development authentication cannot be enabled in production" }
            check(!enabled || isDevelopment) { "Development authentication requires APP_ENV=development" }

            val account = if (enabled) {
                val username = requireNotNull(environment["DEV_AUTH_USERNAME"]?.takeIf(String::isNotBlank)) {
                    "DEV_AUTH_USERNAME is required when DEV_AUTH_ENABLED=true"
                }
                val password = requireNotNull(environment["DEV_AUTH_PASSWORD"]?.takeIf(String::isNotEmpty)) {
                    "DEV_AUTH_PASSWORD is required when DEV_AUTH_ENABLED=true"
                }
                LocalAccount(
                    user = AuthenticatedUser(
                        id = "local:$username",
                        username = username,
                        displayName = if (username == "chris") "Chris" else username,
                    ),
                    passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12)),
                )
            } else {
                null
            }
            return AuthenticationService(account)
        }
    }
}

private const val MAX_LOGIN_FAILURES = 5
private val LOGIN_FAILURE_WINDOW: Duration = Duration.ofMinutes(15)
private val LOGIN_LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)

fun Application.configureAuthentication() {
    val environment = System.getenv()
    val isDevelopment = environment["APP_ENV"].equals("development", ignoreCase = true)
    val isProduction = environment["APP_ENV"].equals("production", ignoreCase = true)
    val encryptionKey = sessionKey(environment, "SESSION_ENCRYPTION_KEY", isProduction)
    val signingKey = sessionKey(environment, "SESSION_SIGNING_KEY", isProduction)
    configureAuthentication(
        authenticationService = AuthenticationService.fromEnvironment(environment),
        secureCookie = !isDevelopment,
        encryptionKey = encryptionKey,
        signingKey = signingKey,
    )
}

private fun Application.configureAuthentication(
    authenticationService: AuthenticationService,
    secureCookie: Boolean,
    encryptionKey: ByteArray,
    signingKey: ByteArray,
) {
    require(encryptionKey.size in setOf(16, 24, 32)) { "Session encryption key must be 16, 24, or 32 bytes" }
    require(signingKey.size >= 32) { "Session signing key must be at least 32 bytes" }
    attributes.put(AUTHENTICATION_SERVICE_ATTRIBUTE, authenticationService)

    intercept(ApplicationCallPipeline.Call) {
        call.response.headers.append("X-Content-Type-Options", "nosniff", safeOnly = true)
        call.response.headers.append("X-Frame-Options", "DENY", safeOnly = true)
        call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin", safeOnly = true)
        call.response.headers.append("Permissions-Policy", "camera=(), microphone=(), geolocation=()", safeOnly = true)
        proceed()
    }

    install(Sessions) {
        cookie<AuthSession>(AUTH_SESSION_COOKIE) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secureCookie
            cookie.extensions["SameSite"] = "lax"
            cookie.maxAgeInSeconds = AUTH_SESSION_LIFETIME.seconds
            transform(SessionTransportTransformerEncrypt(encryptionKey, signingKey))
        }
    }
    install(Authentication) {
        session<AuthSession>("auth-session") {
            validate { session ->
                authenticationService.resolve(session)?.let(::AuthenticatedPrincipal)
            }
        }
    }

    routing {
        get("/auth/csrf") {
            val token = call.request.cookies[CSRF_COOKIE]?.takeIf { it.length == 43 }
                ?: ByteArray(32).also(SecureRandom()::nextBytes)
                    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            call.response.cookies.append(
                Cookie(
                    name = CSRF_COOKIE,
                    value = token,
                    path = "/",
                    secure = secureCookie,
                    httpOnly = false,
                    extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            call.respond(CsrfTokenResponse(token))
        }
        post("/auth/login") {
            if (!call.requireCsrfToken()) return@post
            val request = runCatching { call.receive<LoginRequest>() }.getOrNull()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            when (val loginResult = authenticationService.login(request.username, request.password, call.request.local.remoteHost)) {
                LoginResult.Disabled -> {
                    call.application.log.warn(
                        "Development login is disabled; configure APP_ENV=development and DEV_AUTH_ENABLED=true to enable it",
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        AuthenticationErrorResponse(
                            "Development login is disabled. Configure APP_ENV=development, DEV_AUTH_ENABLED=true, " +
                                "DEV_AUTH_USERNAME, and DEV_AUTH_PASSWORD for the Ktor process.",
                        ),
                    )
                }
                LoginResult.InvalidCredentials -> call.respond(HttpStatusCode.Unauthorized, AuthenticationResponse(false, null))
                LoginResult.RateLimited -> call.respond(HttpStatusCode.TooManyRequests)
                is LoginResult.Authenticated -> {
                    authenticationService.logout(call.sessions.get<AuthSession>())
                    call.sessions.set(loginResult.session)
                    call.respond(AuthenticationResponse(true, loginResult.user))
                }
            }
        }
        post("/auth/logout") {
            if (!call.requireCsrfToken()) return@post
            authenticationService.logout(call.sessions.get<AuthSession>())
            call.sessions.clear<AuthSession>()
            call.respond(HttpStatusCode.NoContent)
        }
        get("/auth/me") {
            val user = call.sessions.get<AuthSession>()?.let(authenticationService::resolve)
            call.respond(AuthenticationResponse(user != null, user))
        }
        get("/auth/status") {
            val user = call.sessions.get<AuthSession>()?.let(authenticationService::resolve)
            call.respond(AuthenticationResponse(user != null, user))
        }
    }
}

internal suspend fun ApplicationCall.requireCsrfToken(): Boolean {
    val cookieToken = request.cookies[CSRF_COOKIE]
    val headerToken = request.header(CSRF_HEADER)
    val valid = cookieToken != null && headerToken != null &&
        MessageDigest.isEqual(cookieToken.toByteArray(StandardCharsets.UTF_8), headerToken.toByteArray(StandardCharsets.UTF_8))
    if (!valid) respond(HttpStatusCode.Forbidden, "CSRF token is missing or invalid")
    return valid
}

internal suspend fun ApplicationCall.requirePermission(permission: Permission): Boolean {
    val user = currentAuthenticatedUser()
    if (user == null) {
        respond(HttpStatusCode.Unauthorized)
        return false
    }
    if (!AuthorizationService.hasPermission(user, permission)) {
        respond(HttpStatusCode.Forbidden)
        return false
    }
    return true
}

internal fun ApplicationCall.currentAuthenticatedUser(): AuthenticatedUser? =
    principal<AuthenticatedPrincipal>()?.user

internal fun Application.authenticationService(): AuthenticationService =
    attributes[AUTHENTICATION_SERVICE_ATTRIBUTE]

internal fun Application.configureAuthenticationForTest(
    authenticationService: AuthenticationService,
    secureCookie: Boolean,
    encryptionKey: ByteArray,
    signingKey: ByteArray,
) {
    configureAuthentication(authenticationService, secureCookie, encryptionKey, signingKey)
}

private fun sessionKey(environment: Map<String, String>, name: String, production: Boolean): ByteArray {
    val configured = environment[name]?.toByteArray(StandardCharsets.UTF_8)
    if (production) {
        return requireNotNull(configured) { "$name must be configured in production" }
    }
    return configured ?: ByteArray(32).also(SecureRandom()::nextBytes)
}