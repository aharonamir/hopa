package com.hopa.backend.accounts

import com.hopa.backend.ServerConfig
import com.hopa.backend.harness.PostgresHarness
import com.hopa.backend.module
import com.hopa.backend.pairing.TokenIssuer
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.Timestamp
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val BASE = 1_763_212_800_000L
private val PUBLIC_KEY = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
private val SELF_WRAPPED_KEY = Base64.getEncoder().encodeToString(ByteArray(60) { 7 })

/** A fake transport that records exactly what it was asked to send. */
private class FakeSmsPort : SmsPort {
    val sent = mutableListOf<Pair<String, String>>()

    override fun send(to: String, code: String) {
        sent.add(to to code)
    }

    fun codes(): List<String> = sent.map { it.second }
}

/**
 * The SMS OTP routes. One Testcontainers database is shared across the suite,
 * so every account uses a freshly minted, unique phone and each test drives its
 * own clock and rate-limiters.
 */
class OtpTest {
    private val db = PostgresHarness.appDatabase()

    private fun config(sms: SmsPort, clock: AtomicLong, smsEnabled: Boolean = true): ServerConfig {
        val accounts = AccountService(db, TokenIssuer(db, clock::get), clock = clock::get)
        return ServerConfig(
            version = "0.1.0",
            accounts = accounts,
            smsEnabled = smsEnabled,
            sms = sms,
            otp = if (smsEnabled) OtpService(accounts, sms, clock = clock::get) else null,
        )
    }

    private fun uniquePhone(): String {
        val n = PHONE_COUNTER.incrementAndGet()
        return "+1556" + n.toString().padStart(7, '0')
    }

    private fun wrongCode(code: String): String =
        ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private suspend fun HttpClient.requestCode(
        phone: String,
        address: String = "198.51.100.60",
    ): HttpResponse = post("/v1/session/otp") {
        header("X-Forwarded-For", address)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("phone", phone) }.toString())
    }

    private suspend fun HttpClient.verify(
        phone: String,
        code: String,
        platform: String = "android",
        name: String = "Pixel 9",
        publicKey: String = PUBLIC_KEY,
        address: String = "198.51.100.61",
    ): HttpResponse = post("/v1/session/otp/verify") {
        header("X-Forwarded-For", address)
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("phone", phone)
                put("code", code)
                put("platform", platform)
                put("name", name)
                put("publicKey", publicKey)
            }.toString(),
        )
    }

    private suspend fun HttpClient.signUp(
        phone: String,
        address: String = "198.51.100.10",
    ): HttpResponse = post("/v1/accounts") {
        header("X-Forwarded-For", address)
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("phone", phone)
                put("password", "correct horse battery staple")
                put("platform", "android")
                put("name", "Pixel 9")
                put("publicKey", PUBLIC_KEY)
                put("alg", "x25519")
                put("selfWrappedKey", SELF_WRAPPED_KEY)
            }.toString(),
        )
    }

    private suspend fun bodyOf(response: HttpResponse): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun errorCode(response: HttpResponse): String =
        bodyOf(response)["code"]!!.jsonPrimitive.content

    private suspend fun errorMessage(response: HttpResponse): String =
        bodyOf(response)["message"]!!.jsonPrimitive.content

    private fun Connection.count(sql: String): Int =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> rows.next(); rows.getInt(1) }
        }

    private fun Connection.countWhere(sql: String, id: String): Int =
        prepareStatement(sql).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    private fun verifiedAt(phone: String): Timestamp? =
        db.read { connection ->
            connection.prepareStatement("select verified_at from accounts where phone = ?").use {
                it.setString(1, phone)
                it.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1) else null }
            }
        }

    @Test
    fun `with SMS disabled both routes answer E_UNSUPPORTED and the port sends nothing`() =
        testApplication {
            val fake = FakeSmsPort()
            application { module(config(fake, AtomicLong(BASE), smsEnabled = false)) }

            val request = client.requestCode(uniquePhone())
            val verify = client.verify(uniquePhone(), "123456")

            assertEquals(HttpStatusCode.ServiceUnavailable, request.status)
            assertEquals("E_UNSUPPORTED", errorCode(request))
            assertEquals(HttpStatusCode.ServiceUnavailable, verify.status)
            assertEquals("E_UNSUPPORTED", errorCode(verify))
            assertTrue(fake.sent.isEmpty(), "the disabled transport must never send")
        }

    @Test
    fun `request then verify issues tokens and creates a signed-in device`() = testApplication {
        val fake = FakeSmsPort()
        val clock = AtomicLong(BASE)
        application { module(config(fake, clock)) }
        val phone = uniquePhone()

        val requested = client.requestCode(phone)
        assertEquals(HttpStatusCode.Accepted, requested.status)
        val code = fake.codes().single()

        val response = client.verify(phone, code)
        assertEquals(HttpStatusCode.Created, response.status)
        val body = bodyOf(response)
        val accountId = body["accountId"]!!.jsonPrimitive.content
        val deviceId = body["deviceId"]!!.jsonPrimitive.content
        val householdId = body["householdId"]!!.jsonPrimitive.content
        assertTrue(body["accessToken"]!!.jsonPrimitive.content.length >= 43)
        assertTrue(body["refreshToken"]!!.jsonPrimitive.content.length >= 43)

        assertEquals(
            1,
            db.read { it.countWhere("select count(*) from accounts where id = ?::uuid", accountId) },
        )
        db.tenant(householdId) { connection ->
            assertEquals(1, connection.count("select count(*) from households"))
            assertEquals(1, connection.count("select count(*) from members"))
            assertEquals(1, connection.count("select count(*) from devices"))
            assertEquals(0, connection.count("select count(*) from household_key_wraps"))
        }
        assertNotNull(verifiedAt(phone), "an OTP provisioning must stamp verified_at")
    }

    @Test
    fun `a used code fails the second time`() = testApplication {
        val fake = FakeSmsPort()
        application { module(config(fake, AtomicLong(BASE))) }
        val phone = uniquePhone()

        assertEquals(HttpStatusCode.Accepted, client.requestCode(phone).status)
        val code = fake.codes().single()
        assertEquals(HttpStatusCode.Created, client.verify(phone, code).status)

        val second = client.verify(phone, code, address = "198.51.100.62")
        assertEquals(HttpStatusCode.Unauthorized, second.status)
        assertEquals("E_AUTH", errorCode(second))
    }

    @Test
    fun `an expired code fails`() = testApplication {
        val fake = FakeSmsPort()
        val clock = AtomicLong(BASE)
        application { module(config(fake, clock)) }
        val phone = uniquePhone()

        assertEquals(HttpStatusCode.Accepted, client.requestCode(phone).status)
        val code = fake.codes().single()

        clock.addAndGet(5 * 60 * 1000 + 1)

        val response = client.verify(phone, code, address = "198.51.100.63")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("E_AUTH", errorCode(response))
    }

    @Test
    fun `an unknown phone is accepted silently and its verify matches a wrong code`() = testApplication {
        val fake = FakeSmsPort()
        application { module(config(fake, AtomicLong(BASE))) }
        val phone = uniquePhone()

        val requested = client.requestCode(phone)
        assertEquals(HttpStatusCode.Accepted, requested.status)
        assertFalse(requested.bodyAsText().contains(phone), "the 202 body must not contain the phone")
        val code = fake.codes().single()

        // A different, unknown phone presenting the same code is a uniform E_AUTH.
        val unknownPhone = uniquePhone()
        val unknownPhoneVerify = client.verify(unknownPhone, code, address = "198.51.100.64")
        assertEquals(HttpStatusCode.Unauthorized, unknownPhoneVerify.status)

        // A wrong code for the original phone is the same E_AUTH.
        val wrongCodeVerify = client.verify(phone, wrongCode(code), address = "198.51.100.65")
        assertEquals(HttpStatusCode.Unauthorized, wrongCodeVerify.status)
        assertEquals(errorCode(unknownPhoneVerify), errorCode(wrongCodeVerify))
        assertEquals(errorMessage(unknownPhoneVerify), errorMessage(wrongCodeVerify))
    }

    @Test
    fun `an OTP login binds to an existing unverified account and stamps verified_at`() =
        testApplication {
            val fake = FakeSmsPort()
            application { module(config(fake, AtomicLong(BASE))) }
            val phone = uniquePhone()

            // Pre-seed an unverified account through the password sign-up route.
            val signedUp = bodyOf(client.signUp(phone))
            val accountId = signedUp["accountId"]!!.jsonPrimitive.content
            assertNull(verifiedAt(phone), "a password sign-up leaves the account unverified")

            assertEquals(HttpStatusCode.Accepted, client.requestCode(phone).status)
            val code = fake.codes().single()

            val response = client.verify(phone, code, address = "198.51.100.66")
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(accountId, bodyOf(response)["accountId"]!!.jsonPrimitive.content)

            // One account row, now verified.
            assertEquals(
                1,
                db.read { it.countWhere("select count(*) from accounts where phone = ?", phone) },
            )
            assertNotNull(verifiedAt(phone), "the OTP login must stamp verified_at")
        }

    @Test
    fun `the code is sent to the normalized E164 number`() = testApplication {
        val fake = FakeSmsPort()
        application { module(config(fake, AtomicLong(BASE))) }

        val response = client.requestCode("+972 50-123-4567")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("+972501234567", fake.sent.single().first)
    }

    @Test
    fun `the third wrong attempt invalidates the code`() = testApplication {
        val fake = FakeSmsPort()
        application { module(config(fake, AtomicLong(BASE))) }
        val phone = uniquePhone()

        assertEquals(HttpStatusCode.Accepted, client.requestCode(phone).status)
        val code = fake.codes().single()
        val wrong = wrongCode(code)

        repeat(3) { i ->
            val attempt = client.verify(phone, wrong, address = "198.51.100.7$i")
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
            assertEquals("E_AUTH", errorCode(attempt))
        }

        // Even the correct code is now spent.
        val final = client.verify(phone, code, address = "198.51.100.73")
        assertEquals(HttpStatusCode.Unauthorized, final.status)
        assertEquals("E_AUTH", errorCode(final))
    }

    @Test
    fun `the fourth code in an hour is refused`() = testApplication {
        val fake = FakeSmsPort()
        application { module(config(fake, AtomicLong(BASE))) }
        val phone = uniquePhone()

        repeat(3) { i ->
            assertEquals(
                HttpStatusCode.Accepted,
                client.requestCode(phone, address = "198.51.100.8$i").status,
            )
        }
        val fourth = client.requestCode(phone, address = "198.51.100.83")
        assertEquals(HttpStatusCode.TooManyRequests, fourth.status)
        assertEquals("E_RATE", errorCode(fourth))
        assertEquals(3, fake.sent.size, "the fourth code must never be sent")
    }

    @Test
    fun `no response body contains the phone or the code`() = testApplication {
        val fake = FakeSmsPort()
        application { module(config(fake, AtomicLong(BASE))) }
        val phone = uniquePhone()

        val requested = client.requestCode(phone)
        val code = fake.codes().single()

        val requestedText = requested.bodyAsText()
        assertFalse(requestedText.contains(phone), "the 202 body must not contain the phone")
        assertFalse(requestedText.contains(phone.removePrefix("+")), "the 202 body must not contain the digits")
        assertFalse(requestedText.contains(code), "the 202 body must not contain the code")

        val verified = client.verify(phone, code, address = "198.51.100.90")
        val verifiedText = verified.bodyAsText()
        assertFalse(verifiedText.contains(phone), "the 201 body must not contain the phone")
        assertFalse(verifiedText.contains(code), "the 201 body must not contain the code")
    }

    private companion object {
        val PHONE_COUNTER = AtomicInteger(0)
    }
}
