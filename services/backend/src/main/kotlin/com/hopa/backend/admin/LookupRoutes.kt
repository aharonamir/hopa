package com.hopa.backend.admin

import com.hopa.backend.ServerConfig
import com.hopa.backend.accounts.Phones
import com.hopa.backend.http.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * `GET /v1/admin/households?query=` (admin Bearer). Resolves a phone, member id
 * or household id to `[{householdId, memberId, displayName, phone, verified,
 * accountStatus}]`; a missing, blank or malformed query is `E_SCHEMA`, and a
 * well-formed query with no match is `[]`. A household device token is refused
 * with 401 `E_AUTH` (the admin resolver only knows `admin_sessions`).
 */
fun Application.lookupRoutes(config: ServerConfig) {
    val lookup = config.householdLookup ?: return

    routing {
        get("/v1/admin/households") {
            val admin = call.adminIdentity(config) ?: return@get
            val query = call.request.queryParameters["query"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (query == null) {
                call.respondError(HttpStatusCode.BadRequest, "E_SCHEMA", "query is required")
                return@get
            }
            if (Phones.normalize(query) == null && !UUID_PATTERN.matches(query)) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "E_SCHEMA",
                    "query must be an E.164 phone number or a uuid",
                )
                return@get
            }
            call.respond(HttpStatusCode.OK, lookup.lookup(admin.adminId, query))
        }
    }
}
