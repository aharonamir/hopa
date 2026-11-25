package com.hopa.backend.console

import com.hopa.backend.ServerConfig
import com.hopa.backend.accounts.Phones
import com.hopa.backend.admin.AccountRemoval
import com.hopa.backend.admin.AdminIdentity
import com.hopa.backend.admin.AdminSessionOutcome
import com.hopa.backend.admin.DiagSender
import com.hopa.backend.admin.UUID_PATTERN
import com.hopa.backend.http.BodyTooLarge
import com.hopa.backend.http.receiveBoundedParameters
import com.hopa.backend.http.respondError
import com.hopa.backend.pairing.Tokens
import com.hopa.backend.pairing.remoteAddress
import com.hopa.backend.store.DeviceInventory
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/**
 * The operations console, server-rendered kotlinx.html pages behind
 * `/console`. The session is the opaque admin token in the `hopa_admin` cookie
 * (HttpOnly/Secure/SameSite=Strict); the token never appears in a URL, a log
 * or a rendered page. Every unauthenticated `/console*` request except the
 * sign-in form redirects to `/console/sign-in`.
 */
fun Application.consoleRoutes(config: ServerConfig) {
    val diag = DiagSender.from(config)

    routing {
        get("/console/sign-in") {
            call.respondHtml(HttpStatusCode.OK, ConsoleViews.signIn(error = false))
        }

        post("/console/sign-in") {
            val sessions = config.adminSessions
            if (sessions == null) {
                call.respondHtml(HttpStatusCode.Unauthorized, ConsoleViews.signIn(error = true))
                return@post
            }
            // Login CSRF: a cross-site form POST could otherwise sign this
            // browser into the attacker's admin account. Reject when a browser
            // Origin names a different host than the one this request reached.
            if (call.crossSite()) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            val params = try {
                call.receiveBoundedParameters()
            } catch (_: BodyTooLarge) {
                call.respondError(
                    HttpStatusCode.PayloadTooLarge,
                    "E_SCHEMA",
                    "request body is larger than 64 KiB",
                )
                return@post
            }
            val email = params["email"].orEmpty()
            val password = params["password"].orEmpty()
            when (val outcome = sessions.signIn(email, password, remoteAddress(call))) {
                is AdminSessionOutcome.Ok -> {
                    call.response.cookies.append(
                        Cookie(
                            name = "hopa_admin",
                            value = outcome.value.token,
                            path = "/",
                            secure = true,
                            httpOnly = true,
                            extensions = mapOf("SameSite" to "Strict"),
                        ),
                    )
                    call.response.headers.append(HttpHeaders.Location, "/console")
                    call.respond(HttpStatusCode.SeeOther)
                }

                is AdminSessionOutcome.Refused ->
                    call.respondHtml(HttpStatusCode.Unauthorized, ConsoleViews.signIn(error = true))
            }
        }

        get("/console") {
            val admin = call.consoleAdminOrRedirect(config) ?: return@get
            call.respondHtml(
                HttpStatusCode.OK,
                ConsoleViews.index(version = config.version, db = config.db.status()),
            )
        }

        get("/console/lookup") {
            val admin = call.consoleAdminOrRedirect(config) ?: return@get
            val lookup = config.householdLookup
            if (lookup == null) {
                call.respondHtml(HttpStatusCode.ServiceUnavailable, ConsoleViews.error(503, "household lookup is not configured"))
                return@get
            }
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            val reveal = call.request.queryParameters["reveal"] == "1"
            if (query.isEmpty()) {
                call.respondHtml(
                    HttpStatusCode.OK,
                    ConsoleViews.lookupForm("Enter a phone number, member id or household id."),
                )
                return@get
            }
            if (Phones.normalize(query) == null && !UUID_PATTERN.matches(query)) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    ConsoleViews.lookupError(query, "query must be an E.164 phone number or a uuid"),
                )
                return@get
            }
            val rows = lookup.lookup(admin.adminId, query).map { row ->
                ConsoleLookupRow(
                    householdId = row.householdId,
                    memberId = row.memberId,
                    displayName = row.displayName,
                    maskedPhone = row.phone?.let { "…" + it.takeLast(4) },
                    verified = row.verified,
                    accountStatus = row.accountStatus,
                    queueDepth = config.db.queueDepth(row.householdId),
                    devices = config.devicesFor(row.householdId),
                    // The full number only on an explicit request, and only into
                    // the response: it still never reaches the access log.
                    fullPhone = if (reveal) row.phone else null,
                )
            }
            call.respondHtml(HttpStatusCode.OK, ConsoleViews.lookupResults(query, rows))
        }

        get("/console/phones") {
            val admin = call.consoleAdminOrRedirect(config) ?: return@get
            val list = config.deviceList
            if (list == null) {
                call.respondHtml(
                    HttpStatusCode.ServiceUnavailable,
                    ConsoleViews.error(503, "device inventory is not configured"),
                )
                return@get
            }
            val reveal = call.request.queryParameters["reveal"]
            val rows = list.list()
            if (reveal != null) {
                val phone = rows.firstOrNull { it.deviceId == reveal }?.phone
                if (phone != null) {
                    // The reveal is the deliberate PII read; the list itself only
                    // ever renders the masked tail.
                    config.log.event(
                        "admin.pii.reveal",
                        mapOf(
                            "admin_id" to admin.adminId,
                            "query_kind" to "device",
                            "query_hash" to Tokens.hex(Tokens.hash(phone)).take(16),
                        ),
                    )
                }
            }
            call.respondHtml(
                HttpStatusCode.OK,
                ConsoleViews.phones(
                    rows.map { row ->
                        ConsolePhoneRow(
                            deviceId = row.deviceId,
                            householdId = row.householdId,
                            platform = row.platform,
                            name = row.name,
                            online = config.registry.online(row.deviceId),
                            lastSeenAt = row.lastSeenAt,
                            revoked = row.revoked,
                            maskedPhone = row.phone?.let { "…" + it.takeLast(4) },
                            fullPhone = if (reveal == row.deviceId) row.phone else null,
                        )
                    },
                ),
            )
        }

        get("/console/device/{id}") {
            val admin = call.consoleAdminOrRedirect(config) ?: return@get
            val deviceId = call.parameters["id"].orEmpty()
                .takeIf { UUID_PATTERN.matches(it) }
                ?.let { UUID.fromString(it).toString() }
            if (deviceId == null) {
                call.respondHtml(HttpStatusCode.BadRequest, ConsoleViews.error(400, "device id must be a uuid"))
                return@get
            }
            val household = call.householdOrNull()
            if (household == null) {
                call.respondHtml(HttpStatusCode.BadRequest, ConsoleViews.error(400, "household is required and must be a uuid"))
                return@get
            }
            val inventory = config.devices as? DeviceInventory
            if (inventory == null) {
                call.respondHtml(HttpStatusCode.ServiceUnavailable, ConsoleViews.error(503, "device inventory is not configured"))
                return@get
            }
            val row = inventory.adminList(household).firstOrNull { it.id == deviceId }
            if (row == null) {
                call.respondHtml(HttpStatusCode.NotFound, ConsoleViews.error(404, "no such device in this household"))
                return@get
            }
            call.respondHtml(
                HttpStatusCode.OK,
                ConsoleViews.device(
                    ConsoleDevice(
                        id = row.id,
                        platform = row.platform,
                        name = row.name,
                        lastSeenAt = row.lastSeenAt,
                        revokedAt = row.revokedAt,
                        online = config.registry.online(row.id),
                    ),
                    household = household,
                ),
            )
        }

        post("/console/device/{id}/diag") {
            val admin = call.consoleAdminOrRedirect(config) ?: return@post
            val deviceId = call.parameters["id"].orEmpty()
                .takeIf { UUID_PATTERN.matches(it) }
                ?.let { UUID.fromString(it).toString() }
            if (deviceId == null) {
                call.respondHtml(HttpStatusCode.BadRequest, ConsoleViews.error(400, "device id must be a uuid"))
                return@post
            }
            val params = try {
                call.receiveBoundedParameters()
            } catch (_: BodyTooLarge) {
                call.respondError(
                    HttpStatusCode.PayloadTooLarge,
                    "E_SCHEMA",
                    "request body is larger than 64 KiB",
                )
                return@post
            }
            val household = params["household"]
                ?.takeIf { UUID_PATTERN.matches(it) }
                ?.let { UUID.fromString(it).toString() }
            if (household == null) {
                call.respondHtml(HttpStatusCode.BadRequest, ConsoleViews.error(400, "household is required and must be a uuid"))
                return@post
            }
            val type = params["type"].orEmpty()
            when (val result = diag.send(admin.adminId, deviceId, household, type, JsonObject(emptyMap()))) {
                is DiagSender.Result.Accepted -> call.respondHtml(
                    HttpStatusCode.Accepted,
                    ConsoleViews.diagAccepted(result.taskId, result.trace, result.state, deviceId),
                )

                is DiagSender.Result.Refused -> call.respondHtml(
                    result.status,
                    ConsoleViews.error(result.status.value, result.message),
                )
            }
        }

        post("/console/account/remove") {
            val admin = call.consoleAdminOrRedirect(config) ?: return@post
            // The same login-CSRF guard as the sign-in form: a cross-site post
            // must never delete a household.
            if (call.crossSite()) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            val removal = config.accountRemoval
            val lookup = config.householdLookup
            if (removal == null || lookup == null) {
                call.respondHtml(
                    HttpStatusCode.ServiceUnavailable,
                    ConsoleViews.error(503, "account removal is not configured"),
                )
                return@post
            }
            val params = try {
                call.receiveBoundedParameters()
            } catch (_: BodyTooLarge) {
                call.respondError(
                    HttpStatusCode.PayloadTooLarge,
                    "E_SCHEMA",
                    "request body is larger than 64 KiB",
                )
                return@post
            }
            val household = params["household"]
                ?.takeIf { UUID_PATTERN.matches(it) }
                ?.let { UUID.fromString(it).toString() }
            val member = params["member"]
                ?.takeIf { UUID_PATTERN.matches(it) }
                ?.let { UUID.fromString(it).toString() }
            if (household == null || member == null) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    ConsoleViews.error(400, "household and member are required uuids"),
                )
                return@post
            }
            val row = lookup.lookup(admin.adminId, household)
                ?.firstOrNull { it.memberId == member }
            if (row == null || row.accountStatus == "none") {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    ConsoleViews.error(400, "no account behind this member"),
                )
                return@post
            }
            val tail = row.phone?.takeLast(4)
            // The page shows "…4998"; accept either the four digits or the
            // exact text it asked for, so following the label always works.
            val typed = params["confirm"]?.trim()?.removePrefix("…")
            if (tail == null || typed != tail) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    ConsoleViews.error(400, "type the last four digits shown to confirm"),
                )
                return@post
            }
            when (val outcome = removal.remove(admin.adminId, row.householdId, row.memberId)) {
                is AccountRemoval.Outcome.Removed -> call.respondHtml(
                    HttpStatusCode.OK,
                    ConsoleViews.removed(outcome.householdId, outcome.devices),
                )

                is AccountRemoval.Outcome.Refused -> call.respondHtml(
                    HttpStatusCode.BadRequest,
                    ConsoleViews.error(400, outcome.message),
                )
            }
        }

        // Catch-all for unknown `/console` sub-paths, registered last so the
        // defined routes above always win. An unauthenticated caller is sent to
        // the sign-in form; an authenticated caller gets an empty 404 (no page
        // content, no token leak).
        route("/console/{...}") {
            handle {
                if (call.consoleIdentity(config) == null) {
                    call.respondRedirect("/console/sign-in")
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

/** The console session: the opaque admin token held in the `hopa_admin` cookie. */
private fun ApplicationCall.consoleIdentity(config: ServerConfig): AdminIdentity? {
    val token = request.cookies["hopa_admin"]?.takeIf { it.isNotEmpty() }
    return token?.let { config.adminAuth?.adminFor(it) }
}

/** Resolves the cookie identity, redirecting to the sign-in form when absent. */
private suspend fun ApplicationCall.consoleAdminOrRedirect(config: ServerConfig): AdminIdentity? {
    val identity = consoleIdentity(config)
    if (identity == null) {
        respondRedirect("/console/sign-in")
    }
    return identity
}

/** The `household` query parameter as a canonical uuid, or null. */
private fun ApplicationCall.householdOrNull(): String? =
    request.queryParameters["household"]
        ?.takeIf { UUID_PATTERN.matches(it) }
        ?.let { UUID.fromString(it).toString() }

/**
 * True when the request carries an `Origin` whose host differs from the host it
 * reached. Browsers send `Origin` on cross-site form POSTs; a matching or absent
 * Origin (curl, tests) is not cross-site. The console is browser-only, so this is
 * the login-CSRF guard.
 */
private fun ApplicationCall.crossSite(): Boolean {
    val origin = request.header(HttpHeaders.Origin) ?: return false
    val originHost = runCatching { java.net.URI(origin).host }.getOrNull() ?: return false
    // A browser always sends Host; fall back to the local bind only for engines
    // (like the test host) that do not set it.
    val requestHost = request.header(HttpHeaders.Host)?.substringBefore(':') ?: request.local.serverHost
    return !originHost.equals(requestHost, ignoreCase = true)
}

/** The device inventory of one household with live presence attached. */
private fun ServerConfig.devicesFor(householdId: String): List<ConsoleDevice> {
    val inventory = devices as? DeviceInventory ?: return emptyList()
    return inventory.adminList(householdId).map { row ->
        ConsoleDevice(
            id = row.id,
            platform = row.platform,
            name = row.name,
            lastSeenAt = row.lastSeenAt,
            revokedAt = row.revokedAt,
            online = registry.online(row.id),
        )
    }
}
