package com.hopa.backend.pairing

import com.hopa.contracts.json.HopaJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The answer to a phone + password sign-up or sign-in: the account and the
 * freshly admitted device's tokens. Tokens are bearer capabilities and are
 * redacted from [toString].
 */
data class SignedInAccount(
    val accountId: String,
    val deviceId: String,
    val householdId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
) {
    override fun toString(): String = "SignedInAccount(accountId=$accountId, deviceId=$deviceId, " +
        "householdId=$householdId, accessToken=<redacted>, refreshToken=<redacted>, " +
        "expiresAt=$expiresAt)"
}

/**
 * The unauthenticated account half of the backend: create an account
 * (`/v1/accounts`) or sign into one (`/v1/session`). The phone is E.164
 * normalized by the caller and is only ever a login lookup; it is scrubbed from
 * every error message, as is the password, so neither can reach a log line.
 */
class AccountClient(
    private val gatewayUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val base = BackendUrls.httpBase(gatewayUrl)

    suspend fun signUp(
        phone: String,
        password: String,
        platform: String,
        name: String,
        publicKey: String,
        alg: String,
        selfWrappedKey: String,
        pushToken: String?,
    ): SignedInAccount {
        val body = buildJsonObject {
            put("phone", phone)
            put("password", password)
            put("platform", platform)
            put("name", name)
            put("publicKey", publicKey)
            put("alg", alg)
            put("selfWrappedKey", selfWrappedKey)
            if (pushToken != null) put("pushToken", pushToken)
        }
        return account(
            post(path = "/v1/accounts", body = body.toString(), secrets = listOf(phone, password)),
        )
    }

    suspend fun signIn(
        phone: String,
        password: String,
        platform: String,
        name: String,
        publicKey: String,
        pushToken: String?,
    ): SignedInAccount {
        val body = buildJsonObject {
            put("phone", phone)
            put("password", password)
            put("platform", platform)
            put("name", name)
            put("publicKey", publicKey)
            if (pushToken != null) put("pushToken", pushToken)
        }
        return account(
            post(path = "/v1/session", body = body.toString(), secrets = listOf(phone, password)),
        )
    }

    /** The launch probe: true only when `/v1/health` answers 2xx in time. */
    suspend fun health(): Boolean = withContext(dispatcher) {
        val request = Request.Builder()
            .url(base + "/v1/health")
            .get()
            .build()
        val client = http.newBuilder().callTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            return@withContext false
        }
        response.use { it.isSuccessful }
    }

    private fun account(payload: JsonObject): SignedInAccount {
        val account = SignedInAccount(
            accountId = payload["accountId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            deviceId = payload["deviceId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            householdId = payload["householdId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            accessToken = payload["accessToken"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            refreshToken = payload["refreshToken"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            expiresAt = payload["expiresAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        if (account.accountId.isBlank() || account.deviceId.isBlank() ||
            account.householdId.isBlank() || account.accessToken.isBlank() ||
            account.refreshToken.isBlank() || account.expiresAt.isBlank()
        ) {
            throw IllegalStateException("account response is incomplete")
        }
        return account
    }

    private suspend fun post(path: String, body: String, secrets: List<String>): JsonObject = withContext(dispatcher) {
        val request = Request.Builder()
            .url(base + path)
            .post(body.toRequestBody(JSON))
            .build()
        val client = http.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw BackendPairingFailure.Unavailable(
                scrub("backend unreachable: ${error.message.orEmpty()}", secrets),
            )
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val message = scrub(refusal(it.code, text), secrets)
                throw when {
                    it.code in 400..499 -> BackendPairingFailure.Refused(it.code, message)
                    else -> BackendPairingFailure.Unavailable(message)
                }
            }
            parse(text)
        }
    }

    private fun refusal(status: Int, text: String): String {
        val error = runCatching { HopaJson.element.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = runCatching { error?.get("code")?.jsonPrimitive?.contentOrNull }.getOrNull()
        val message = runCatching { error?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return "account refused: ${code ?: "HTTP $status"} ${message.orEmpty()}".trim()
    }

    /** Never echo the response: a malformed body may itself contain tokens. */
    private fun parse(text: String): JsonObject = try {
        HopaJson.element.parseToJsonElement(text).jsonObject
    } catch (error: Exception) {
        throw IllegalStateException("account returned no JSON object")
    }

    /** A refusal may come back echoing the phone or password; it never leaves here. */
    private fun scrub(message: String, secrets: List<String>): String = secrets.fold(message) { text, secret ->
        if (secret.isBlank()) text else text.replace(secret, "<redacted>")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val TIMEOUT_SECONDS = 20L
        const val HEALTH_TIMEOUT_SECONDS = 5L
    }
}
