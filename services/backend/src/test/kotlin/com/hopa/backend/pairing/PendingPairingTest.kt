package com.hopa.backend.pairing

import com.hopa.backend.ServerConfig
import com.hopa.backend.harness.PostgresHarness
import com.hopa.backend.harness.newDevice
import com.hopa.backend.harness.newHousehold
import com.hopa.backend.harness.seedHousehold
import com.hopa.backend.module
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
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingPairingTest {
    private val db = PostgresHarness.appDatabase()
    private val clock = AtomicLong(1_763_212_800_000)
    private val key = ByteArray(32) { index -> index.toByte() }

    private fun config() = ServerConfig(
        version = "0.1.0",
        pendingPairings = PendingPairingService(db, clock = clock::get),
    )

    private suspend fun HttpClient.mint(
        platform: String = "android",
        name: String = "Pixel 9",
        publicKey: String = Base64.getEncoder().encodeToString(key),
        alg: String = "x25519",
        address: String = "198.51.100.10",
    ): HttpResponse = post("/v1/pair/pending") {
        header("X-Forwarded-For", address)
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("platform", platform)
                put("name", name)
                put("publicKey", publicKey)
                put("alg", alg)
            }.toString(),
        )
    }

    private suspend fun HttpClient.resolve(
        secret: String,
        address: String = "198.51.100.10",
    ): HttpResponse = post("/v1/pair/pending/resolve") {
        header("X-Forwarded-For", address)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("sessionSecret", secret) }.toString())
    }

    private fun bodyOf(response: String): JsonObject = Json.parseToJsonElement(response).jsonObject

    private suspend fun errorCode(response: HttpResponse): String =
        bodyOf(response.bodyAsText())["code"]!!.jsonPrimitive.content

    private suspend fun errorMessage(response: HttpResponse): String =
        bodyOf(response.bodyAsText())["message"]!!.jsonPrimitive.content

    private fun fingerprintOf(bytes: ByteArray): String =
        Tokens.hex(MessageDigest.getInstance("SHA-256").digest(bytes)).take(8)

    private fun hashHex(secret: String): String = Tokens.hex(Tokens.hash(secret))

    @Test
    fun `an algorithm other than x25519 is E_SCHEMA`() = testApplication {
        application { module(config()) }

        val response = client.mint(alg = "rsa")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("E_SCHEMA", errorCode(response))
    }

    @Test
    fun `a public key that is not 32 bytes is E_SCHEMA`() = testApplication {
        application { module(config()) }

        val short = client.mint(
            publicKey = Base64.getEncoder().encodeToString(ByteArray(31) { 1 }),
        )
        val long = client.mint(
            publicKey = Base64.getEncoder().encodeToString(ByteArray(33) { 1 }),
            address = "198.51.100.11",
        )

        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("E_SCHEMA", errorCode(short))
        assertEquals(HttpStatusCode.BadRequest, long.status)
    }

    @Test
    fun `a blank or oversized name is E_SCHEMA`() = testApplication {
        application { module(config()) }

        val blank = client.mint(name = "")
        val long = client.mint(name = "n".repeat(101), address = "198.51.100.11")

        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals("E_SCHEMA", errorCode(blank))
        assertEquals(HttpStatusCode.BadRequest, long.status)
        assertEquals("E_SCHEMA", errorCode(long))
    }

    @Test
    fun `a platform other than android or windows is E_SCHEMA`() = testApplication {
        application { module(config()) }

        val response = client.mint(platform = "ios")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("E_SCHEMA", errorCode(response))
    }

    @Test
    fun `the sixth mint from one address in a minute is E_RATE`() = testApplication {
        application { module(config()) }

        repeat(5) {
            assertNotEquals(
                HttpStatusCode.TooManyRequests,
                client.mint(address = "203.0.113.9").status,
            )
        }
        val sixth = client.mint(address = "203.0.113.9")

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals("E_RATE", errorCode(sixth))
    }

    @Test
    fun `a LAN scheme override mints a ws QR`() = testApplication {
        application {
            module(
                ServerConfig(
                    version = "0.1.0",
                    pendingPairings = PendingPairingService(
                        db,
                        clock = clock::get,
                        publicScheme = "ws",
                    ),
                ),
            )
        }

        val response = client.mint()

        assertEquals(HttpStatusCode.Created, response.status)
        val qr = bodyOf(response.bodyAsText())["qr"]!!.jsonPrimitive.content
        assertTrue(qr.contains("&u=ws://"), qr)
        assertFalse(qr.contains("&u=wss://"), qr)
    }

    @Test
    fun `a mint returns a QR with the secret and stores only its hash`() = testApplication {
        application { module(config()) }

        val response = client.mint()

        assertEquals(HttpStatusCode.Created, response.status)
        val body = bodyOf(response.bodyAsText())
        val secret = body["secret"]!!.jsonPrimitive.content
        val qr = body["qr"]!!.jsonPrimitive.content
        assertTrue(
            Regex("^hopa://pair\\?v=1&s=[A-Za-z0-9_-]+&u=wss://.+/v1/gateway$").matches(qr),
        )
        assertFalse(qr.contains("h="))
        assertTrue(qr.contains("s=$secret"))
        assertEquals(
            clock.get() + 300_000,
            java.time.Instant.parse(body["expiresAt"]!!.jsonPrimitive.content).toEpochMilli(),
        )

        val stored = db.token(hashHex(secret)) { connection ->
            connection.prepareStatement(
                "select id::text, code_hash, public_key, household_id::text, " +
                    "concat_ws('|', platform, name, alg) " +
                    "from pending_pairings where code_hash = decode(?, 'hex')",
            ).use { statement ->
                statement.setString(1, hashHex(secret))
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "the minted row must be readable by its own hash" }
                    StoredRow(
                        id = rows.getString(1),
                        codeHash = rows.getBytes(2),
                        publicKey = rows.getBytes(3),
                        householdId = rows.getString(4),
                        text = rows.getString(5),
                    )
                }
            }
        }
        assertArrayEquals(Tokens.hash(secret), stored.codeHash)
        assertArrayEquals(key, stored.publicKey)
        assertEquals(body["sessionId"]!!.jsonPrimitive.content, stored.id)
        assertNull(stored.householdId)
        assertFalse(stored.text.contains(secret))
    }

    @Test
    fun `two mints give different secrets and sessions`() = testApplication {
        application { module(config()) }

        val first = bodyOf(client.mint(address = "198.51.100.12").bodyAsText())
        val second = bodyOf(client.mint(address = "198.51.100.12").bodyAsText())

        assertNotEquals(
            first["secret"]!!.jsonPrimitive.content,
            second["secret"]!!.jsonPrimitive.content,
        )
        assertNotEquals(
            first["sessionId"]!!.jsonPrimitive.content,
            second["sessionId"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `resolving an unknown secret is E_SCHEMA`() = testApplication {
        application { module(config()) }

        val response = client.resolve(Tokens.mint())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("E_SCHEMA", errorCode(response))
    }

    @Test
    fun `a pending session resolves to its row fields`() = testApplication {
        application { module(config()) }
        val minted = bodyOf(client.mint().bodyAsText())

        val response = client.resolve(minted["secret"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.OK, response.status)
        val state = bodyOf(response.bodyAsText())
        assertEquals("pending", state["state"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", state["name"]!!.jsonPrimitive.content)
        assertEquals("android", state["platform"]!!.jsonPrimitive.content)
        assertEquals(minted["sessionId"]!!.jsonPrimitive.content, state["sessionId"]!!.jsonPrimitive.content)
        assertEquals(minted["expiresAt"]!!.jsonPrimitive.content, state["expiresAt"]!!.jsonPrimitive.content)

        val publicKey = state["publicKey"]!!.jsonPrimitive.content
        assertFalse(publicKey.contains("="))
        assertArrayEquals(key, Base64.getUrlDecoder().decode(publicKey))
        val fingerprint = state["fingerprint"]!!.jsonPrimitive.content
        assertTrue(Regex("^[0-9a-f]{8}$").matches(fingerprint))
        assertEquals(fingerprintOf(key), fingerprint)
    }

    @Test
    fun `a session past its expiry resolves as expired`() = testApplication {
        application { module(config()) }
        val minted = bodyOf(client.mint().bodyAsText())

        clock.addAndGet(300_001)
        val response = client.resolve(minted["secret"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.OK, response.status)
        val state = bodyOf(response.bodyAsText())
        assertEquals("expired", state["state"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", state["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a vouched session resolves as vouched`() = testApplication {
        application { module(config()) }
        val minted = bodyOf(client.mint().bodyAsText())
        val secret = minted["secret"]!!.jsonPrimitive.content
        val updated = db.token(hashHex(secret)) { connection ->
            connection.prepareStatement(
                "update pending_pairings set vouched_at = now() " +
                    "where code_hash = decode(?, 'hex')",
            ).use { statement ->
                statement.setString(1, hashHex(secret))
                statement.executeUpdate()
            }
        }
        assertEquals(1, updated)

        val state = bodyOf(client.resolve(secret).bodyAsText())

        assertEquals("vouched", state["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a claimed session resolves as claimed even after expiry`() = testApplication {
        application { module(config()) }
        val minted = bodyOf(client.mint().bodyAsText())
        val secret = minted["secret"]!!.jsonPrimitive.content
        val updated = db.token(hashHex(secret)) { connection ->
            connection.prepareStatement(
                "update pending_pairings set claimed_at = now() " +
                    "where code_hash = decode(?, 'hex')",
            ).use { statement ->
                statement.setString(1, hashHex(secret))
                statement.executeUpdate()
            }
        }
        assertEquals(1, updated)
        clock.addAndGet(300_001)

        val state = bodyOf(client.resolve(secret).bodyAsText())

        assertEquals("claimed", state["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a vouched session that expired resolves as expired`() = testApplication {
        application { module(config()) }
        val minted = bodyOf(client.mint().bodyAsText())
        val secret = minted["secret"]!!.jsonPrimitive.content
        val updated = db.token(hashHex(secret)) { connection ->
            connection.prepareStatement(
                "update pending_pairings set vouched_at = now() " +
                    "where code_hash = decode(?, 'hex')",
            ).use { statement ->
                statement.setString(1, hashHex(secret))
                statement.executeUpdate()
            }
        }
        assertEquals(1, updated)
        clock.addAndGet(300_001)

        val state = bodyOf(client.resolve(secret).bodyAsText())

        assertEquals("expired", state["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty or wrong-length secret is uniformly unknown`() = testApplication {
        application { module(config()) }

        val unknown = client.resolve(Tokens.mint())
        val empty = client.resolve("")
        val short = client.resolve("not-a-secret", address = "198.51.100.21")

        for (response in listOf(unknown, empty, short)) {
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("E_SCHEMA", errorCode(response))
            assertEquals("unknown pairing session", errorMessage(response))
        }
    }

    @Test
    fun `the thirty-first resolve from one address in a minute is E_RATE`() = testApplication {
        application { module(config()) }
        val minted = bodyOf(client.mint().bodyAsText())
        val secret = minted["secret"]!!.jsonPrimitive.content

        repeat(30) {
            assertEquals(
                HttpStatusCode.OK,
                client.resolve(secret, address = "203.0.113.30").status,
            )
        }
        val denied = client.resolve(secret, address = "203.0.113.30")

        assertEquals(HttpStatusCode.TooManyRequests, denied.status)
        assertEquals("E_RATE", errorCode(denied))

        // The window is driven by the injected clock: one minute later the same
        // poller is allowed again, while the session itself is still live.
        clock.addAndGet(60_001)
        assertEquals(
            HttpStatusCode.OK,
            client.resolve(secret, address = "203.0.113.30").status,
        )
    }

    @Test
    fun `a resolve needs only the secret and leaks no household`() = testApplication {
        application { module(config()) }
        val household = newHousehold()
        seedHousehold(db, household, newDevice(), UUID.randomUUID().toString())
        val minted = bodyOf(client.mint().bodyAsText())
        val secret = minted["secret"]!!.jsonPrimitive.content
        // Task 4 binds a vouched row to a tenant; the poller still holds only
        // the secret, so the token-hash branch of the policy must keep it visible.
        val bound = db.token(hashHex(secret)) { connection ->
            connection.prepareStatement(
                "update pending_pairings set household_id = ?::uuid " +
                    "where code_hash = decode(?, 'hex')",
            ).use { statement ->
                statement.setString(1, household)
                statement.setString(2, hashHex(secret))
                statement.executeUpdate()
            }
        }
        assertEquals(1, bound)

        val response = client.resolve(secret)

        assertEquals(HttpStatusCode.OK, response.status)
        val state = bodyOf(response.bodyAsText())
        assertEquals("pending", state["state"]!!.jsonPrimitive.content)
        assertFalse(state.containsKey("householdId"))
    }

    private data class StoredRow(
        val id: String,
        val codeHash: ByteArray,
        val publicKey: ByteArray,
        val householdId: String?,
        val text: String,
    )
}
