package com.hopa.backend.admin

import com.hopa.backend.ServerConfig
import com.hopa.backend.accounts.Passwords
import com.hopa.backend.harness.PostgresHarness
import com.hopa.backend.harness.newDevice
import com.hopa.backend.harness.newHousehold
import com.hopa.backend.log.EventLog
import com.hopa.backend.module
import com.hopa.backend.pairing.PgAuth
import com.hopa.backend.pairing.Tokens
import com.hopa.backend.store.PgDevices
import com.hopa.backend.store.PgTaskStore
import com.hopa.backend.store.PostgresProbe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The admin session issuer and the household lookup. An admin session is minted
 * through the real `POST /v1/admin/session`; the lookup goes through the real
 * `GET /v1/admin/households` against the `hopa_admin` definer functions, with
 * the PII access log captured in memory. The app role still reads zero `members`
 * rows without a tenant GUC.
 */
class LookupTest {
    private val db = PostgresHarness.appDatabase()
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val log = EventLog { name, fields -> events += name to fields }
    private val random = java.security.SecureRandom()

    @BeforeEach
    fun setUp() {
        events.clear()
    }

    private fun config() = ServerConfig(
        version = "0.1.0",
        auth = PgAuth(db),
        devices = PgDevices(db),
        db = PostgresProbe(db),
        tasks = PgTaskStore(db),
        log = log,
        adminAuth = AdminAuth(db),
        adminSessions = AdminSessions(db),
        householdLookup = HouseholdLookup(db, log),
    )

    // -- fixtures -----------------------------------------------------------

    private data class Admin(val id: String, val token: String)

    /** Mints an admin session row directly, for the lookup tests. */
    private fun seedAdminSession(): Admin {
        val adminId = UUID.randomUUID().toString()
        val token = Tokens.mint()
        db.read { connection ->
            connection.prepareStatement(
                "insert into admins(id, email, password_hash, role) " +
                    "values (?::uuid, ?, 'not-used', 'admin')",
            ).use {
                it.setString(1, adminId)
                it.setString(2, "admin-${UUID.randomUUID()}@hopa.test")
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into admin_sessions(id, admin_id, token_hash, expires_at) " +
                    "values (gen_random_uuid(), ?::uuid, ?, ?)",
            ).use {
                it.setString(1, adminId)
                it.setBytes(2, Tokens.hash(token))
                it.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000)))
                it.executeUpdate()
            }
        }
        return Admin(adminId, token)
    }

    /** Inserts an admin with a known password, for the sign-in tests. */
    private fun seedAdmin(email: String, password: String): String {
        val adminId = UUID.randomUUID().toString()
        db.read { connection ->
            connection.prepareStatement(
                "insert into admins(id, email, password_hash, role) values (?::uuid, ?, ?, 'admin')",
            ).use {
                it.setString(1, adminId)
                it.setString(2, email)
                it.setString(3, Passwords.hash(password.toCharArray()))
                it.executeUpdate()
            }
        }
        return adminId
    }

    private data class Fixture(
        val householdId: String,
        val memberId: String,
        val accountId: String,
        val phone: String,
    )

    /** A verified account linked to an owner member of a household of one. */
    private fun seedAccountHousehold(verified: Boolean = true): Fixture {
        val householdId = newHousehold()
        val memberId = UUID.randomUUID().toString()
        val accountId = UUID.randomUUID().toString()
        val phone = "+9725" + (1..8).joinToString("") { random.nextInt(10).toString() }
        db.read { connection ->
            connection.prepareStatement(
                "insert into accounts(id, phone, password_hash, verified_at) " +
                    "values (?::uuid, ?, 'hash', " + (if (verified) "now()" else "null") + ")",
            ).use {
                it.setString(1, accountId)
                it.setString(2, phone)
                it.executeUpdate()
            }
        }
        db.tenant(householdId) { connection ->
            connection.prepareStatement("insert into households(id, name) values (?::uuid, 'Home')").use {
                it.setString(1, householdId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into members(id, household_id, role, display_name, account_id) " +
                    "values (?::uuid, ?::uuid, 'owner', 'Alice', ?::uuid)",
            ).use {
                it.setString(1, memberId)
                it.setString(2, householdId)
                it.setString(3, accountId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into devices(id, household_id, member_id, platform, name) " +
                    "values (?::uuid, ?::uuid, ?::uuid, 'android', 'Pixel')",
            ).use {
                it.setString(1, newDevice())
                it.setString(2, householdId)
                it.setString(3, memberId)
                it.executeUpdate()
            }
        }
        return Fixture(householdId, memberId, accountId, phone)
    }

    /** An operator-paired household: an owner member with no account. */
    private fun seedOperatorPairedHousehold(): Pair<String, String> {
        val householdId = newHousehold()
        val memberId = UUID.randomUUID().toString()
        db.tenant(householdId) { connection ->
            connection.prepareStatement("insert into households(id, name) values (?::uuid, 'Home')").use {
                it.setString(1, householdId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into members(id, household_id, role, display_name) " +
                    "values (?::uuid, ?::uuid, 'owner', 'Bob')",
            ).use {
                it.setString(1, memberId)
                it.setString(2, householdId)
                it.executeUpdate()
            }
        }
        return householdId to memberId
    }

    private fun accessToken(household: String, device: String): String {
        val token = Tokens.mint()
        db.tenant(household) { connection ->
            connection.prepareStatement(
                "insert into access_tokens(id, device_id, household_id, token_hash, expires_at) " +
                    "values (gen_random_uuid(), ?::uuid, ?::uuid, ?, ?)",
            ).use {
                it.setString(1, device)
                it.setString(2, household)
                it.setBytes(3, Tokens.hash(token))
                it.setTimestamp(
                    4,
                    Timestamp.from(Instant.ofEpochMilli(System.currentTimeMillis() + 60_000)),
                )
                it.executeUpdate()
            }
        }
        return token
    }

    // -- HTTP helpers -------------------------------------------------------

    private suspend fun HttpClient.signIn(email: String, password: String): HttpResponse =
        post("/v1/admin/session") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("email", email); put("password", password) }.toString())
        }

    private suspend fun HttpClient.lookup(token: String?, query: String?): HttpResponse =
        get("/v1/admin/households") {
            if (token != null) header("Authorization", "Bearer $token")
            if (query != null) parameter("query", query)
        }

    private fun code(response: String): String? =
        Json.parseToJsonElement(response).jsonObject["code"]?.jsonPrimitive?.content

    private fun message(response: String): String? =
        Json.parseToJsonElement(response).jsonObject["message"]?.jsonPrimitive?.content

    private fun hash16(value: String): String = Tokens.hex(Tokens.hash(value)).take(16)

    private fun piiReads() = events.filter { it.first == "admin.pii.read" }

    private fun allEventsText(): String =
        events.joinToString("\n") { (name, fields) -> name + fields.values.joinToString() }

    // -- tests --------------------------------------------------------------

    @Test
    fun `seeded admin signs in and a wrong password is a uniform E_AUTH`() = testApplication {
        val email = "admin-${UUID.randomUUID()}@hopa.test"
        val password = "correct horse battery staple"
        seedAdmin(email, password)
        application { module(config()) }

        val ok = client.signIn(email, password)
        assertEquals(HttpStatusCode.Created, ok.status)
        val body = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertTrue(body["token"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(body["expiresAt"]!!.jsonPrimitive.content.isNotEmpty())

        val wrong = client.signIn(email, "wrong password")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("E_AUTH", code(wrong.bodyAsText()))

        val unknown = client.signIn("unknown-${UUID.randomUUID()}@hopa.test", password)
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("E_AUTH", code(unknown.bodyAsText()))
        assertEquals(message(wrong.bodyAsText()), message(unknown.bodyAsText()))
    }

    @Test
    fun `the sixth sign-in attempt is E_RATE`() = testApplication {
        val email = "admin-${UUID.randomUUID()}@hopa.test"
        seedAdmin(email, "correct horse battery staple")
        application { module(config()) }

        for (attempt in 1..5) {
            val response = client.signIn(email, "wrong password")
            assertEquals(HttpStatusCode.Unauthorized, response.status, "attempt $attempt")
            assertEquals("E_AUTH", code(response.bodyAsText()), "attempt $attempt")
        }

        val sixth = client.signIn(email, "wrong password")
        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals("E_RATE", code(sixth.bodyAsText()))
    }

    @Test
    fun `lookup by phone, member id and household id return the same household`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        for (query in listOf(fixture.phone, fixture.memberId, fixture.householdId)) {
            val response = client.lookup(admin.token, query)
            assertEquals(HttpStatusCode.OK, response.status, "query $query")
            val rows = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, rows.size, "query $query")
            val row = rows.single().jsonObject
            assertEquals(fixture.householdId, row["householdId"]!!.jsonPrimitive.content)
            assertEquals(fixture.memberId, row["memberId"]!!.jsonPrimitive.content)
            assertEquals("Alice", row["displayName"]!!.jsonPrimitive.content)
            assertEquals(fixture.phone, row["phone"]!!.jsonPrimitive.content)
            assertEquals(true, row["verified"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("verified", row["accountStatus"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `an operator-paired member has no phone and accountStatus none`() = testApplication {
        val (householdId, memberId) = seedOperatorPairedHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.lookup(admin.token, memberId)
        assertEquals(HttpStatusCode.OK, response.status)
        val row = Json.parseToJsonElement(response.bodyAsText()).jsonArray.single().jsonObject
        assertEquals(householdId, row["householdId"]!!.jsonPrimitive.content)
        assertEquals(memberId, row["memberId"]!!.jsonPrimitive.content)
        assertEquals("Bob", row["displayName"]!!.jsonPrimitive.content)
        assertEquals(null, row["phone"])
        assertEquals(false, row["verified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("none", row["accountStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tombstoned account is hidden on member and phone lookups`() = testApplication {
        val fixture = seedAccountHousehold()
        db.read { connection ->
            connection.prepareStatement(
                "update accounts set deleted_at = now() where id = ?::uuid",
            ).use {
                it.setString(1, fixture.accountId)
                assertEquals(1, it.executeUpdate())
            }
        }
        val admin = seedAdminSession()
        application { module(config()) }

        val memberResponse = client.lookup(admin.token, fixture.memberId)
        assertEquals(HttpStatusCode.OK, memberResponse.status)
        val row = Json.parseToJsonElement(memberResponse.bodyAsText()).jsonArray.single().jsonObject
        assertEquals(fixture.memberId, row["memberId"]!!.jsonPrimitive.content)
        assertEquals(null, row["phone"])
        assertEquals(false, row["verified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("none", row["accountStatus"]!!.jsonPrimitive.content)

        val phoneResponse = client.lookup(admin.token, fixture.phone)
        assertEquals(HttpStatusCode.OK, phoneResponse.status)
        assertEquals(0, Json.parseToJsonElement(phoneResponse.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `a well-formed but unknown uuid returns an empty array`() = testApplication {
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.lookup(admin.token, "00000000-0000-0000-0000-000000000000")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `a missing, blank or malformed query is E_SCHEMA and logs nothing`() = testApplication {
        val admin = seedAdminSession()
        application { module(config()) }

        var response = client.lookup(admin.token, null)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("E_SCHEMA", code(response.bodyAsText()))

        response = client.lookup(admin.token, "   ")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("E_SCHEMA", code(response.bodyAsText()))

        response = client.lookup(admin.token, "not-a-phone-or-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("E_SCHEMA", code(response.bodyAsText()))

        assertTrue(piiReads().isEmpty(), "a refused query must not write a PII line")
    }

    @Test
    fun `a household device token on the admin lookup is E_AUTH`() = testApplication {
        val fixture = seedAccountHousehold()
        val device = newDevice()
        db.tenant(fixture.householdId) { connection ->
            connection.prepareStatement(
                "insert into devices(id, household_id, member_id, platform, name) " +
                    "values (?::uuid, ?::uuid, ?::uuid, 'windows', 'Desk')",
            ).use {
                it.setString(1, device)
                it.setString(2, fixture.householdId)
                it.setString(3, fixture.memberId)
                it.executeUpdate()
            }
        }
        val deviceToken = accessToken(fixture.householdId, device)
        application { module(config()) }

        val response = client.lookup(deviceToken, fixture.phone)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("E_AUTH", code(response.bodyAsText()))
    }

    @Test
    fun `the access log holds one pii read line per request without the raw query`() =
        testApplication {
            val fixture = seedAccountHousehold()
            val admin = seedAdminSession()
            application { module(config()) }

            client.lookup(admin.token, fixture.phone)
            client.lookup(admin.token, fixture.memberId)
            client.lookup(admin.token, fixture.householdId)

            val reads = piiReads()
            assertEquals(3, reads.size)
            for (read in reads) {
                assertEquals(setOf("admin_id", "query_kind", "query_hash"), read.second.keys)
                assertEquals(admin.id, read.second["admin_id"])
            }
            assertEquals("phone", reads[0].second["query_kind"])
            assertEquals("uuid", reads[1].second["query_kind"])
            assertEquals("uuid", reads[2].second["query_kind"])
            assertEquals(hash16(fixture.phone), reads[0].second["query_hash"])
            assertEquals(hash16(fixture.memberId), reads[1].second["query_hash"])
            assertEquals(hash16(fixture.householdId), reads[2].second["query_hash"])

            val text = allEventsText()
            assertFalse(text.contains(fixture.phone))
            assertFalse(text.contains(fixture.phone.filter { it.isDigit() }))
            assertFalse(text.contains(fixture.memberId))
            assertFalse(text.contains(fixture.householdId))
        }

    @Test
    fun `the app role reads no members without a tenant while the lookup route resolves them`() =
        testApplication {
            val fixture = seedAccountHousehold()
            val admin = seedAdminSession()

            // As hopa_app with no household/token GUC, FORCE RLS hides every row.
            val directCount = db.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from members").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
            assertEquals(0, directCount)

            application { module(config()) }
            val response = client.lookup(admin.token, fixture.memberId)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                fixture.memberId,
                Json.parseToJsonElement(response.bodyAsText())
                    .jsonArray.single().jsonObject["memberId"]!!.jsonPrimitive.content,
            )
        }
}
