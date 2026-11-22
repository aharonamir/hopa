package com.hopa.backend.admin

import com.hopa.backend.ServerConfig
import com.hopa.backend.http.BodyTooLarge
import com.hopa.backend.http.receiveBounded
import com.hopa.backend.http.respondError
import com.hopa.backend.pairing.remoteAddress
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** An admin console sign-in: email + password. */
@Serializable
data class AdminSignInRequest(val email: String, val password: String)

/**
 * `POST /v1/admin/session`. Unauthenticated and rate limited; an unknown email
 * and a wrong password answer the same `E_AUTH`, and success returns a 201
 * `{token, expiresAt}` for an 8-hour opaque session token.
 */
fun Application.adminSessionRoutes(config: ServerConfig) {
    val sessions = config.adminSessions ?: return

    routing {
        post("/v1/admin/session") {
            val request = try {
                call.receiveBounded(AdminSignInRequest.serializer())
            } catch (_: BodyTooLarge) {
                call.respondError(
                    HttpStatusCode.PayloadTooLarge,
                    "E_SCHEMA",
                    "request body is larger than 64 KiB",
                )
                return@post
            } catch (_: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "E_SCHEMA", "invalid JSON body")
                return@post
            }
            when (val outcome = sessions.signIn(request.email, request.password, remoteAddress(call))) {
                is AdminSessionOutcome.Ok -> call.respond(HttpStatusCode.Created, outcome.value)
                is AdminSessionOutcome.Refused -> {
                    val status = when (outcome.code) {
                        "E_AUTH" -> HttpStatusCode.Unauthorized
                        "E_RATE" -> HttpStatusCode.TooManyRequests
                        else -> HttpStatusCode.BadRequest
                    }
                    call.respondError(status, outcome.code, outcome.message, outcome.retryable)
                }
            }
        }
    }
}
