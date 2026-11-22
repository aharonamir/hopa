package com.hopa.backend.accounts

import com.hopa.backend.ServerConfig
import com.hopa.backend.http.BodyTooLarge
import com.hopa.backend.http.receiveBounded
import com.hopa.backend.http.respondError
import com.hopa.backend.pairing.remoteAddress
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** A new account: phone + password, and the phone's first device with its own key wrap. */
@Serializable
data class SignUpRequest(
    val phone: String,
    val password: String,
    val platform: String,
    val name: String,
    val publicKey: String,
    val alg: String,
    val selfWrappedKey: String,
    val pushToken: String? = null,
)

/** Signing into an existing account; a new signed-in device, no key wrap. */
@Serializable
data class SignInRequest(
    val phone: String,
    val password: String,
    val platform: String,
    val name: String,
    val publicKey: String,
    val pushToken: String? = null,
)

/**
 * The account and the freshly admitted device's tokens. The phone and the
 * password hash never appear here: the phone is only a login lookup, never on
 * the wire.
 */
@Serializable
data class AccountCreated(
    val accountId: String,
    val deviceId: String,
    val householdId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
)

fun Application.accountRoutes(config: ServerConfig) {
    val api = config.accounts ?: return

    routing {
        post("/v1/accounts") {
            val request = try {
                call.receiveBounded(SignUpRequest.serializer())
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
            when (val outcome = api.signUp(request, remoteAddress(call))) {
                is AccountOutcome.Ok -> call.respond(HttpStatusCode.Created, outcome.value)
                is AccountOutcome.Refused -> call.refuse(outcome)
            }
        }

        post("/v1/session") {
            val request = try {
                call.receiveBounded(SignInRequest.serializer())
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
            when (val outcome = api.signIn(request, remoteAddress(call))) {
                is AccountOutcome.Ok -> call.respond(HttpStatusCode.Created, outcome.value)
                is AccountOutcome.Refused -> call.refuse(outcome)
            }
        }
    }
}

internal suspend fun ApplicationCall.refuse(refusal: AccountOutcome.Refused) {
    val status = when (refusal.code) {
        "E_AUTH" -> HttpStatusCode.Unauthorized
        "E_RATE" -> HttpStatusCode.TooManyRequests
        else -> HttpStatusCode.BadRequest
    }
    respondError(status, refusal.code, refusal.message, refusal.retryable)
}
