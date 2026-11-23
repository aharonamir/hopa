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
import java.time.Instant
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val PUBLIC_KEY = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
private val SELF_WRAPPED_KEY = Base64.getEncoder().encodeToString(ByteArray(60) { 7 })

/**
 * The phone + password sign-up and sign-in routes. One Testcontainers database
 * is shared across the suite, so every account uses a freshly minted, unique
 * phone and the rate-limit assertions use a fixed clock.
 */
class AccountRoutesTest {
    private val db = PostgresHarness.appDatabase()
    private val clock = AtomicLong(1_763_212_800_000)

    private fun config() = ServerConfig(
        version = "0.1.0",
        accounts = AccountService(db, TokenIssuer(db, clock::get), clock = clock::get),
    )

    private fun uniquePhone(): String {
        val n = PHONE_COUNTER.incrementAndGet()
        return "+1555" + n.toString().padStart(7, '0')
    }

    private suspend fun HttpClient.signUp(
        phone: String,
        password: String = "correct horse battery staple",
        platform: String = "android",
        name: String = "Pixel 9",
        publicKey: String = PUBLIC_KEY,
        alg: String = "x25519",
        selfWrappedKey: String = SELF_WRAPPED_KEY,
        pushToken: String? = "fcm-token",
        address: String = "198.51.100.10",
    ): HttpResponse = post("/v1/accounts") {
        header("X-Forwarded-For", address)
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("phone", phone)
                put("password", password)
                put("platform", platform)
                put("name", name)
                put("publicKey", publicKey)
                put("alg", alg)
                put("selfWrappedKey", selfWrappedKey)
                if (pushToken != null) put("pushToken", pushToken)
            }.toString(),
        )
    }

    private suspend fun HttpClient.signIn(
        phone: String,
        password: String = "correct horse battery staple",
        platform: String = "android",
        name: String = "Pixel 9",
        publicKey: String = PUBLIC_KEY,
        pushToken: String? = "fcm-token",
        address: String = "198.51.100.20",
    ): HttpResponse = post("/v1/session") {
        header("X-Forwarded-For", address)
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("phone", phone)
                put("password", password)
                put("platform", platform)
                put("name", name)
                put("publicKey", publicKey)
                if (pushToken != null) put("pushToken", pushToken)
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

    /** Row counts across all tenants, read through the container superuser. */
    private fun globalCounts(): List<Int> =
        PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            listOf(
                connection.count("select count(*) from accounts"),
                connection.count("select count(*) from households"),
                connection.count("select count(*) from members"),
                connection.count("select count(*) from devices"),
                connection.count("select count(*) from household_key_wraps"),
            )
        }

    private fun installDeviceFailureTrigger() {
        PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create function public.fail_device_insert() returns trigger as
                    'begin raise exception ''forced device failure''; end;'
                    language plpgsql
                    """.trimIndent(),
                )
                statement.execute(
                    "create trigger fail_device_insert_trigger before insert on devices " +
                        "for each row execute function public.fail_device_insert()",
                )
            }
        }
    }

    private fun dropDeviceFailureTrigger() {
        PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("drop trigger if exists fail_device_insert_trigger on devices")
                statement.execute("drop function if exists public.fail_device_insert()")
            }
        }
    }

    /**
     * Simulates the race the partial unique index closes: a duplicate phone
     * that slips past the read-then-insert pre-check and raises SQLState 23505
     * on the accounts insert. The sign-up must refuse with the same uniform
     * E_AUTH, never surface a 500.
     */
    private fun installAccountDuplicateTrigger() {
        PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create function public.fail_account_insert() returns trigger as
                    'begin raise exception ''duplicate phone'' using errcode = ''23505''; end;'
                    language plpgsql
                    """.trimIndent(),
                )
                statement.execute(
                    "create trigger fail_account_insert_trigger before insert on accounts " +
                        "for each row execute function public.fail_account_insert()",
                )
            }
        }
    }

    private fun dropAccountDuplicateTrigger() {
        PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("drop trigger if exists fail_account_insert_trigger on accounts")
                statement.execute("drop function if exists public.fail_account_insert()")
            }
        }
    }

    @Test
    fun `a sign-up creates the account household owner device and wrap in one transaction`() =
        testApplication {
            application { module(config()) }
            val phone = uniquePhone()

            val response = client.signUp(phone)

            assertEquals(HttpStatusCode.Created, response.status)
            val body = bodyOf(response)
            val accountId = body["accountId"]!!.jsonPrimitive.content
            val deviceId = body["deviceId"]!!.jsonPrimitive.content
            val householdId = body["householdId"]!!.jsonPrimitive.content
            assertTrue(body["accessToken"]!!.jsonPrimitive.content.length >= 43)
            assertTrue(body["refreshToken"]!!.jsonPrimitive.content.length >= 43)
            assertEquals(
                clock.get() + 15 * 60 * 1000,
                Instant.parse(body["expiresAt"]!!.jsonPrimitive.content).toEpochMilli(),
            )

            // The account is global; the rest are tenant rows visible to the new household.
            assertEquals(
                1,
                db.read { it.countWhere("select count(*) from accounts where id = ?::uuid", accountId) },
            )
            db.tenant(householdId) { connection ->
                assertEquals(1, connection.count("select count(*) from households"))
                assertEquals(1, connection.count("select count(*) from members"))
                assertEquals(1, connection.count("select count(*) from devices"))
                assertEquals(1, connection.count("select count(*) from household_key_wraps"))

                // The owner member is linked to the account, and the wrap is the
                // bytes the phone sent, never re-encoded by the server.
                assertEquals(
                    1,
                    connection.countWhere(
                        "select count(*) from members where role = 'owner' and account_id = ?::uuid",
                        accountId,
                    ),
                )
                assertEquals(1, connection.countWhere("select count(*) from devices where id = ?::uuid", deviceId))
                val wrap = connection.prepareStatement(
                    "select wrapped_key from household_key_wraps where device_id = ?::uuid",
                ).use { statement ->
                    statement.setString(1, deviceId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getBytes(1) }
                }
                assertEquals(SELF_WRAPPED_KEY, Base64.getEncoder().encodeToString(wrap))
            }
        }

    @Test
    fun `a forced device insert failure leaves zero rows`() = testApplication {
        application { module(config()) }
        installDeviceFailureTrigger()
        try {
            val before = globalCounts()

            val response = runCatching { client.signUp(uniquePhone(), address = "198.51.100.40") }.getOrNull()

            assertEquals(before, globalCounts(), "a failed device insert must leave every table unchanged")
            if (response != null) {
                assertEquals(HttpStatusCode.InternalServerError, response.status)
            }
        } finally {
            dropDeviceFailureTrigger()
        }
    }

    @Test
    fun `a concurrent duplicate phone is the same E_AUTH and leaves no rows`() = testApplication {
        application { module(config()) }
        installAccountDuplicateTrigger()
        try {
            val before = globalCounts()

            val response = client.signUp(uniquePhone(), address = "198.51.100.47")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("E_AUTH", errorCode(response))
            assertEquals("invalid phone or password", errorMessage(response))
            assertEquals(before, globalCounts(), "a duplicate-phone refusal must leave every table unchanged")
        } finally {
            dropAccountDuplicateTrigger()
        }
    }

    @Test
    fun `a duplicate live phone is the same E_AUTH as a wrong password`() = testApplication {
        application { module(config()) }
        val phone = uniquePhone()
        val password = "correct horse battery staple"

        assertEquals(HttpStatusCode.Created, client.signUp(phone, password = password).status)

        val duplicate = client.signUp(phone, password = password, address = "198.51.100.41")
        val wrongPassword = client.signIn(phone, password = "not the password", address = "198.51.100.42")

        assertEquals(HttpStatusCode.Unauthorized, duplicate.status)
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals("E_AUTH", errorCode(duplicate))
        assertEquals("E_AUTH", errorCode(wrongPassword))
        assertEquals(errorMessage(duplicate), errorMessage(wrongPassword))
    }

    @Test
    fun `an invalid phone is E_SCHEMA`() = testApplication {
        application { module(config()) }

        val response = client.signUp("not-a-phone")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("E_SCHEMA", errorCode(response))
    }

    @Test
    fun `a tombstoned account cannot sign in and its phone cannot re-register`() = testApplication {
        application { module(config()) }
        val phone = uniquePhone()
        val password = "correct horse battery staple"

        assertEquals(HttpStatusCode.Created, client.signUp(phone, password = password).status)
        db.read { connection ->
            connection.prepareStatement(
                "update accounts set deleted_at = now() where phone = ?",
            ).use { statement ->
                statement.setString(1, phone)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val signIn = client.signIn(phone, password = password, address = "198.51.100.43")
        val reRegister = client.signUp(phone, password = password, address = "198.51.100.44")

        assertEquals(HttpStatusCode.Unauthorized, signIn.status)
        assertEquals("E_AUTH", errorCode(signIn))
        assertEquals(HttpStatusCode.Unauthorized, reRegister.status)
        assertEquals("E_AUTH", errorCode(reRegister))
    }

    @Test
    fun `the sixth attempt from one address is E_RATE`() = testApplication {
        application { module(config()) }
        val address = "203.0.113.7"

        repeat(5) {
            assertNotEquals(
                HttpStatusCode.TooManyRequests,
                client.signUp(uniquePhone(), address = address).status,
            )
        }
        val sixth = client.signUp(uniquePhone(), address = address)

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals("E_RATE", errorCode(sixth))
    }

    @Test
    fun `a deleted account is skipped by the sign-in lookup`() = testApplication {
        application { module(config()) }
        val phone = uniquePhone()
        val password = "correct horse battery staple"

        val signedUp = bodyOf(client.signUp(phone, password = password))
        val householdId = signedUp["householdId"]!!.jsonPrimitive.content

        db.read { connection ->
            connection.prepareStatement(
                "update accounts set deleted_at = now() where phone = ?",
            ).use { statement ->
                statement.setString(1, phone)
                assertEquals(1, statement.executeUpdate())
            }
        }

        // The correct password is still refused: the lookup skips the deleted row.
        val signIn = client.signIn(phone, password = password, address = "198.51.100.45")

        assertEquals(HttpStatusCode.Unauthorized, signIn.status)
        assertEquals("E_AUTH", errorCode(signIn))
        assertEquals(1, db.tenant(householdId) { it.count("select count(*) from devices") })
    }

    @Test
    fun `the response never contains the phone number`() = testApplication {
        application { module(config()) }
        val phone = uniquePhone()

        val response = client.signUp(phone)

        assertEquals(HttpStatusCode.Created, response.status)
        val text = response.bodyAsText()
        assertFalse(text.contains(phone), "the response must not contain the phone")
        assertFalse(text.contains(phone.removePrefix("+")), "the response must not contain the digits")
    }

    @Test
    fun `a sign-in creates a device and tokens but no household key wrap`() = testApplication {
        application { module(config()) }
        val phone = uniquePhone()
        val password = "correct horse battery staple"

        val signedUp = bodyOf(client.signUp(phone, password = password))
        val accountId = signedUp["accountId"]!!.jsonPrimitive.content
        val householdId = signedUp["householdId"]!!.jsonPrimitive.content

        val response = client.signIn(phone, password = password, address = "198.51.100.46")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = bodyOf(response)
        assertEquals(accountId, body["accountId"]!!.jsonPrimitive.content)
        assertEquals(householdId, body["householdId"]!!.jsonPrimitive.content)
        assertTrue(body["accessToken"]!!.jsonPrimitive.content.length >= 43)

        db.tenant(householdId) { connection ->
            assertEquals(2, connection.count("select count(*) from devices"))
            assertEquals(2, connection.count("select count(*) from access_tokens"))
            assertEquals(2, connection.count("select count(*) from refresh_tokens"))
            assertEquals(1, connection.count("select count(*) from household_key_wraps"))
        }
    }

    private companion object {
        val PHONE_COUNTER = AtomicInteger(0)
    }
}
