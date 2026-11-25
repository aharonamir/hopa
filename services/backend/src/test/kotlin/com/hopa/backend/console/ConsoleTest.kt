package com.hopa.backend.console

import com.hopa.backend.ServerConfig
import com.hopa.backend.accounts.AccountOutcome
import com.hopa.backend.accounts.AccountService
import com.hopa.backend.accounts.Passwords
import com.hopa.backend.accounts.SignUpRequest
import com.hopa.backend.admin.AdminAuth
import com.hopa.backend.admin.AccountRemoval
import com.hopa.backend.admin.AdminSessions
import com.hopa.backend.admin.HouseholdLookup
import com.hopa.backend.auth.DeviceIdentity
import com.hopa.backend.gateway.DeviceRegistry
import com.hopa.backend.harness.PostgresHarness
import com.hopa.backend.harness.newDevice
import com.hopa.backend.harness.newHousehold
import com.hopa.backend.log.EventLog
import com.hopa.backend.module
import com.hopa.backend.pairing.PgAuth
import com.hopa.backend.pairing.TokenIssuer
import com.hopa.backend.pairing.Tokens
import com.hopa.backend.store.PgDevices
import com.hopa.backend.store.PgTaskStore
import com.hopa.backend.store.PostgresProbe
import com.hopa.backend.trace.PgTraceStore
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The admin console pages: server-rendered kotlinx.html behind `/console`,
 * authenticated by the `hopa_admin` cookie holding the opaque admin token.
 * The test client is plain HTTP, so the cookie is sent explicitly.
 */
class ConsoleTest {
    private val db = PostgresHarness.appDatabase()
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val log = EventLog { name, fields -> events += name to fields }
    private val traceStore = PgTraceStore(db)
    private val random = java.security.SecureRandom()

    @BeforeEach
    fun setUp() {
        events.clear()
    }

    private fun config(registry: DeviceRegistry = DeviceRegistry()) = ServerConfig(
        version = "0.1.0",
        auth = PgAuth(db),
        devices = PgDevices(db),
        db = PostgresProbe(db),
        tasks = PgTaskStore(db),
        log = log,
        registry = registry,
        adminAuth = AdminAuth(db),
        adminSessions = AdminSessions(db),
        householdLookup = HouseholdLookup(db, log),
        accountRemoval = AccountRemoval(db, PgDevices(db), registry::kick, log),
        traces = traceStore,
        traceQuery = traceStore,
    )

    // -- fixtures -----------------------------------------------------------

    private data class Admin(val id: String, val token: String)

    private data class Fixture(
        val householdId: String,
        val memberId: String,
        val accountId: String,
        val phone: String,
        val deviceId: String,
    )

    /** Mints an admin session row directly, for the cookie-authenticated tests. */
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

    /** Inserts an admin with a known password, for the sign-in form tests. */
    private fun seedAdmin(email: String, password: String) {
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
    }

    /** A unique E.164 phone whose last four digits are `4567`. */
    private fun uniquePhone(): String =
        "+9725" + (1..4).joinToString("") { random.nextInt(10).toString() } + "4567"

    /** A verified account linked to an owner member of a household of one. */
    private fun seedAccountHousehold(
        phone: String = uniquePhone(),
        displayName: String = "Alice",
    ): Fixture {
        val householdId = newHousehold()
        val memberId = UUID.randomUUID().toString()
        val accountId = UUID.randomUUID().toString()
        val deviceId = newDevice()
        db.read { connection ->
            connection.prepareStatement(
                "insert into accounts(id, phone, password_hash, verified_at) " +
                    "values (?::uuid, ?, 'hash', now())",
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
                    "values (?::uuid, ?::uuid, 'owner', ?, ?::uuid)",
            ).use {
                it.setString(1, memberId)
                it.setString(2, householdId)
                it.setString(3, displayName)
                it.setString(4, accountId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into devices(id, household_id, member_id, platform, name) " +
                    "values (?::uuid, ?::uuid, ?::uuid, 'android', 'Pixel')",
            ).use {
                it.setString(1, deviceId)
                it.setString(2, householdId)
                it.setString(3, memberId)
                it.executeUpdate()
            }
        }
        return Fixture(householdId, memberId, accountId, phone, deviceId)
    }

    private fun addDevice(household: String, member: String, platform: String, name: String): String {
        val device = newDevice()
        db.tenant(household) { connection ->
            connection.prepareStatement(
                "insert into devices(id, household_id, member_id, platform, name) " +
                    "values (?::uuid, ?::uuid, ?::uuid, ?, ?)",
            ).use {
                it.setString(1, device)
                it.setString(2, household)
                it.setString(3, member)
                it.setString(4, platform)
                it.setString(5, name)
                it.executeUpdate()
            }
        }
        return device
    }

    /** Seeds a task and a task event whose bodies carry the sentinel strings. */
    private fun seedSentinelTask(
        household: String,
        member: String,
        device: String,
        taskSentinel: String,
        eventSentinel: String,
    ) {
        val taskId = UUID.randomUUID().toString()
        db.tenant(household) { connection ->
            connection.prepareStatement(
                "insert into tasks(id, household_id, member_id, from_device, to_device, message_id, " +
                    "state, trace_id, body, deadline_at) " +
                    "values (?::uuid, ?::uuid, ?::uuid, null, ?::uuid, ?::uuid, 'queued', ?, ?, now() + interval '1 hour')",
            ).use {
                it.setString(1, taskId)
                it.setString(2, household)
                it.setString(3, member)
                it.setString(4, device)
                it.setString(5, UUID.randomUUID().toString())
                it.setString(6, "trace-$taskSentinel")
                it.setBytes(7, """{"sentinel":"$taskSentinel"}""".toByteArray(Charsets.UTF_8))
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into task_events(task_id, household_id, member_id, seq, kind, body, at) " +
                    "values (?::uuid, ?::uuid, ?::uuid, 0, 'create', ?, now())",
            ).use {
                it.setString(1, taskId)
                it.setString(2, household)
                it.setString(3, member)
                it.setBytes(4, """{"eventSentinel":"$eventSentinel"}""".toByteArray(Charsets.UTF_8))
                it.executeUpdate()
            }
        }
    }

    // -- HTTP helpers -------------------------------------------------------

    private suspend fun HttpClient.console(token: String?, path: String): HttpResponse =
        get(path) {
            if (token != null) header("Cookie", "hopa_admin=$token")
        }

    private suspend fun HttpClient.signInForm(email: String, password: String): HttpResponse =
        submitForm(
            url = "/console/sign-in",
            formParameters = Parameters.build {
                append("email", email)
                append("password", password)
            },
        )

    private suspend fun HttpClient.diagForm(
        token: String,
        device: String,
        household: String,
        type: String,
    ): HttpResponse = submitForm(
        url = "/console/device/$device/diag",
        formParameters = Parameters.build {
            append("type", type)
            append("household", household)
        },
    ) {
        header("Cookie", "hopa_admin=$token")
    }

    // -- tests --------------------------------------------------------------

    @Test
    fun `unauthenticated console redirects to sign-in and the form itself does not`() = testApplication {
        application { module(config()) }
        val noRedirect = createClient { followRedirects = false }

        val form = noRedirect.get("/console/sign-in")
        assertEquals(HttpStatusCode.OK, form.status)

        for (path in listOf(
            "/console",
            "/console/lookup?query=x",
            "/console/device/00000000-0000-0000-0000-000000000000?household=00000000-0000-0000-0000-000000000000",
        )) {
            val response = noRedirect.get(path)
            assertEquals(HttpStatusCode.Found, response.status, path)
            assertEquals("/console/sign-in", response.headers[HttpHeaders.Location], path)
        }
    }

    @Test
    fun `sign-in sets secure cookies and never echoes the token`() = testApplication {
        val email = "admin-${UUID.randomUUID()}@hopa.test"
        val password = "correct horse battery staple"
        seedAdmin(email, password)
        application { module(config()) }

        val wrong = client.signInForm(email, "wrong password")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertTrue(wrong.bodyAsText().contains("Email or password is not valid"))

        val ok = client.signInForm(email, password)
        assertEquals(HttpStatusCode.SeeOther, ok.status)
        assertEquals("/console", ok.headers[HttpHeaders.Location])

        val setCookie = ok.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        assertTrue(setCookie!!.contains("hopa_admin="), setCookie)
        assertTrue(setCookie.contains("HttpOnly"), setCookie)
        assertTrue(setCookie.contains("Secure"), setCookie)
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie)
        assertTrue(setCookie.contains("Path=/"), setCookie)

        val token = setCookie.substringAfter("hopa_admin=").substringBefore(";")
        assertTrue(token.isNotBlank())
        assertFalse(ok.bodyAsText().contains(token), "the token must not be in the sign-in response")

        val console = client.console(token, "/console")
        assertEquals(HttpStatusCode.OK, console.status)
        assertFalse(console.bodyAsText().contains(token), "the token must not be in a rendered page")
    }

    @Test
    fun `a signed-in lookup page shows the household id and the masked phone`() = testApplication {
        val phone = uniquePhone()
        val fixture = seedAccountHousehold(phone = phone)
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.console(admin.token, "/console/lookup?query=${fixture.householdId}")
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertTrue(html.contains(fixture.householdId), html)
        assertTrue(html.contains("…4567"), html)
        assertFalse(html.contains(phone), "the full phone must not appear")
    }

    @Test
    fun `the lookup page lists both devices with their online state`() = testApplication {
        val fixture = seedAccountHousehold()
        addDevice(fixture.householdId, fixture.memberId, "windows", "Desk")
        val admin = seedAdminSession()
        val registry = DeviceRegistry()
        registry.join(
            DeviceRegistry.Session(
                DeviceIdentity(fixture.deviceId, fixture.householdId, fixture.memberId),
                Channel(Channel.UNLIMITED),
            ),
        )
        application { module(config(registry)) }

        val html = client.console(admin.token, "/console/lookup?query=${fixture.householdId}").bodyAsText()
        assertTrue(html.contains("Pixel"), html)
        assertTrue(html.contains("Desk"), html)
        assertTrue(html.contains("online"), html)
        assertTrue(html.contains("offline"), html)
    }

    @Test
    fun `a diag form post returns 202 and the page shows the audit confirmation`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.diagForm(admin.token, fixture.deviceId, fixture.householdId, "diag.ping")
        assertEquals(HttpStatusCode.Accepted, response.status)
        val html = response.bodyAsText()
        assertTrue(html.contains("Task id:"), html)
        assertTrue(html.contains("Trace:"), html)
        assertTrue(html.contains("State:"), html)

        val audits = events.filter { it.first == "diag.send" }
        assertEquals(1, audits.size)
        val fields = audits.single().second
        assertEquals(setOf("admin_id", "household_id", "device_id", "task_id", "trace"), fields.keys)
        assertEquals(admin.id, fields["admin_id"])
        assertEquals(fixture.householdId, fields["household_id"])
        assertEquals(fixture.deviceId, fields["device_id"])

        // The confirmation page names the identifiers, never a body.
        val taskId = fields["task_id"]!!
        val trace = fields["trace"]!!
        assertTrue(html.contains(taskId), html)
        assertTrue(html.contains(trace), html)
        assertTrue(html.contains("queued"), html)
    }

    @Test
    fun `no page renders task payloads or events`() = testApplication {
        val fixture = seedAccountHousehold()
        val taskSentinel = "SENTINEL_TASK_${UUID.randomUUID()}"
        val eventSentinel = "SENTINEL_EVENT_${UUID.randomUUID()}"
        seedSentinelTask(fixture.householdId, fixture.memberId, fixture.deviceId, taskSentinel, eventSentinel)
        val admin = seedAdminSession()
        application { module(config()) }

        for (path in listOf(
            "/console",
            "/console/lookup?query=${fixture.householdId}",
            "/console/device/${fixture.deviceId}?household=${fixture.householdId}",
        )) {
            val html = client.console(admin.token, path).bodyAsText()
            assertFalse(html.contains(taskSentinel), "task sentinel leaked on $path")
            assertFalse(html.contains(eventSentinel), "event sentinel leaked on $path")
        }

        val diag = client.diagForm(admin.token, fixture.deviceId, fixture.householdId, "diag.ping")
        assertFalse(diag.bodyAsText().contains(taskSentinel))
        assertFalse(diag.bodyAsText().contains(eventSentinel))
    }

    @Test
    fun `an HTML-injection display name is escaped`() = testApplication {
        val fixture = seedAccountHousehold(displayName = "<script>alert(1)</script>")
        val admin = seedAdminSession()
        application { module(config()) }

        val html = client.console(admin.token, "/console/lookup?query=${fixture.householdId}").bodyAsText()
        assertTrue(html.contains("&lt;script&gt;"), html)
        assertFalse(html.contains("<script>"), html)
    }

    @Test
    fun `unknown console sub-paths redirect unauthenticated and 404 authenticated`() = testApplication {
        val admin = seedAdminSession()
        application { module(config()) }
        val noRedirect = createClient { followRedirects = false }

        val unauth = noRedirect.get("/console/does-not-exist")
        assertEquals(HttpStatusCode.Found, unauth.status)
        assertEquals("/console/sign-in", unauth.headers[HttpHeaders.Location])

        val auth = noRedirect.get("/console/does-not-exist") {
            header("Cookie", "hopa_admin=${admin.token}")
        }
        assertEquals(HttpStatusCode.NotFound, auth.status)
        assertFalse(auth.bodyAsText().contains(admin.token), "an unknown path must not leak the token")
    }

    @Test
    fun `the device page needs a household`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        var response = client.console(admin.token, "/console/device/${fixture.deviceId}")
        assertEquals(HttpStatusCode.BadRequest, response.status)

        response = client.console(admin.token, "/console/device/${fixture.deviceId}?household=not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a device page for another household is 404`() = testApplication {
        val fixture = seedAccountHousehold()
        val other = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.console(
            admin.token,
            "/console/device/${other.deviceId}?household=${fixture.householdId}",
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `sign-in rejects a cross-site Origin`() = testApplication {
        val email = "admin-${UUID.randomUUID()}@hopa.test"
        val password = "correct horse battery staple"
        seedAdmin(email, password)
        application { module(config()) }

        val response = client.submitForm(
            url = "/console/sign-in",
            formParameters = Parameters.build {
                append("email", email)
                append("password", password)
            },
        ) {
            header("Origin", "https://evil.example")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertFalse(
            response.headers.contains(HttpHeaders.SetCookie),
            "a cross-site sign-in must not set the admin cookie",
        )
    }

    @Test
    fun `an oversized sign-in form is refused with 413`() = testApplication {
        val email = "admin-${UUID.randomUUID()}@hopa.test"
        application { module(config()) }

        val response = client.submitForm(
            url = "/console/sign-in",
            formParameters = Parameters.build {
                append("email", "a".repeat(70_000))
                append("password", "x")
            },
        )

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `the console removes an account when the tail matches`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.removeAccount(
            admin.token,
            fixture.householdId,
            fixture.memberId,
            confirm = fixture.phone.takeLast(4),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertTrue(html.contains("Account removed"), html)
        assertFalse(householdExists(fixture.householdId), "the household must be gone")
        assertFalse(accountExists(fixture.accountId), "the account row must be gone")
        val audited = events.single { it.first == "admin.account.remove" }
        assertEquals(admin.id, audited.second["admin_id"])
        assertEquals(fixture.householdId, audited.second["household_id"])
        assertFalse(audited.second.containsKey("phone"))
    }

    @Test
    fun `the tail may be typed with the leading ellipsis the page shows`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.removeAccount(
            admin.token,
            fixture.householdId,
            fixture.memberId,
            confirm = "…${fixture.phone.takeLast(4)}",
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertFalse(householdExists(fixture.householdId))
    }

    @Test
    fun `a wrong tail deletes nothing`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.removeAccount(
            admin.token,
            fixture.householdId,
            fixture.memberId,
            confirm = "0000",
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(householdExists(fixture.householdId))
        assertTrue(accountExists(fixture.accountId))
        assertTrue(events.none { it.first == "admin.account.remove" })
    }

    @Test
    fun `a cross-site removal is refused and deletes nothing`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val response = client.removeAccount(
            admin.token,
            fixture.householdId,
            fixture.memberId,
            confirm = fixture.phone.takeLast(4),
        ) {
            header("Origin", "https://evil.example")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(householdExists(fixture.householdId))
        assertTrue(accountExists(fixture.accountId))
    }

    @Test
    fun `the erased phone can register again`() = testApplication {
        val fixture = seedAccountHousehold()
        val admin = seedAdminSession()
        application { module(config()) }

        val removed = client.removeAccount(
            admin.token,
            fixture.householdId,
            fixture.memberId,
            fixture.phone.takeLast(4),
        )
        assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())

        // The console route's own account service is not wired in this test
        // config, so the re-registration rule is pinned on the service itself.
        val accounts = AccountService(db, TokenIssuer(db))
        val outcome = accounts.signUp(
            SignUpRequest(
                phone = fixture.phone,
                password = "hopademo2026",
                platform = "android",
                name = "Pixel",
                publicKey = base64Key(),
                alg = "x25519",
                selfWrappedKey = base64Key(),
            ),
            remoteAddress = "198.51.100.9",
        )

        assertTrue(outcome is AccountOutcome.Ok, outcome.toString())
    }

    /** A fresh 32-byte key as standard base64, which the account routes accept. */
    private fun base64Key(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun householdExists(householdId: String): Boolean = db.tenant(householdId) { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from households").use { rows ->
                rows.next()
                rows.getInt(1) > 0
            }
        }
    }

    private fun accountExists(accountId: String): Boolean = db.read { connection ->
        connection.prepareStatement("select count(*) from accounts where id = ?::uuid").use {
            it.setString(1, accountId)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1) > 0
            }
        }
    }

    private suspend fun HttpClient.removeAccount(
        token: String,
        household: String,
        member: String,
        confirm: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = submitForm(
        url = "/console/account/remove",
        formParameters = Parameters.build {
            append("household", household)
            append("member", member)
            append("confirm", confirm)
        },
    ) {
        header("Cookie", "hopa_admin=$token")
        block()
    }
}
