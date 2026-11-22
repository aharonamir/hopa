package com.hopa.backend.accounts

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

/** A request for a one-time code. The phone is only a login lookup, never returned. */
@Serializable
data class OtpRequest(val phone: String)

/** Verifying a code and admitting a signed-in device; the device algorithm is always x25519. */
@Serializable
data class OtpVerifyRequest(
    val phone: String,
    val code: String,
    val platform: String,
    val name: String,
    val publicKey: String,
    val pushToken: String? = null,
)

/** The uniform 202 acknowledgement; identical whether or not the phone has an account. */
@Serializable
data class OtpRequested(val accepted: Boolean = true)

/**
 * The two SMS OTP routes. They exist even when the feature is off so a disabled
 * deployment answers `E_UNSUPPORTED` rather than 404; the flag check happens
 * before the body is read or any code is generated or sent.
 */
fun Application.otpRoutes(config: ServerConfig) {
    routing {
        post("/v1/session/otp") {
            val otp = config.otp
            if (!config.smsEnabled || otp == null) {
                call.respondError(
                    HttpStatusCode.ServiceUnavailable,
                    "E_UNSUPPORTED",
                    "SMS OTP is not enabled on this deployment",
                )
                return@post
            }
            val request = try {
                call.receiveBounded(OtpRequest.serializer())
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
            when (val outcome = otp.requestCode(request, remoteAddress(call))) {
                is AccountOutcome.Ok -> call.respond(HttpStatusCode.Accepted, outcome.value)
                is AccountOutcome.Refused -> call.refuse(outcome)
            }
        }

        post("/v1/session/otp/verify") {
            val otp = config.otp
            if (!config.smsEnabled || otp == null) {
                call.respondError(
                    HttpStatusCode.ServiceUnavailable,
                    "E_UNSUPPORTED",
                    "SMS OTP is not enabled on this deployment",
                )
                return@post
            }
            val request = try {
                call.receiveBounded(OtpVerifyRequest.serializer())
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
            when (val outcome = otp.verify(request, remoteAddress(call))) {
                is AccountOutcome.Ok -> call.respond(HttpStatusCode.Created, outcome.value)
                is AccountOutcome.Refused -> call.refuse(outcome)
            }
        }
    }
}
