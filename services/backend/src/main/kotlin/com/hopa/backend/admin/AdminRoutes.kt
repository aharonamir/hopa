package com.hopa.backend.admin

import com.hopa.backend.ServerConfig
import com.hopa.backend.http.BodyTooLarge
import com.hopa.backend.http.receiveBounded
import com.hopa.backend.http.respondError
import com.hopa.backend.store.DeviceInventory
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One device as the admin inventory renders it; presence comes from the registry. */
@Serializable
data class AdminDeviceView(
    val id: String,
    val platform: String,
    val name: String,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
    val online: Boolean,
)

/** The diagnostic request names its household explicitly (R26). */
@Serializable
data class DiagRequest(
    val household: String,
    val type: String,
    val body: JsonObject? = null,
)

@Serializable
data class DiagAccepted(val taskId: String, val trace: String, val state: String)

/**
 * The operations console: the household's device inventory and the diagnostic
 * sender. It can list devices and send one of three plaintext diagnostics; it
 * never reads a household's tasks or turns.
 */
fun Application.adminRoutes(config: ServerConfig) {
    val inventory = config.devices as? DeviceInventory
    val diag = DiagSender.from(config)

    routing {
        get("/v1/admin/devices") {
            val admin = call.adminIdentity(config) ?: return@get
            if (!call.adminLookupsAvailable(config)) return@get
            val raw = call.request.queryParameters["household"]
            val household = raw?.takeIf { UUID_PATTERN.matches(it) }?.let { UUID.fromString(it).toString() }
            if (household == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "E_SCHEMA",
                    "household must be a uuid",
                )
                return@get
            }
            if (inventory == null) {
                call.respondError(
                    HttpStatusCode.ServiceUnavailable,
                    "E_UNSUPPORTED",
                    "device inventory is not configured",
                )
                return@get
            }
            // The admin read goes through admin_list_devices (owner hopa_admin,
            // BYPASSRLS); the tenant GUC is no longer this path's mechanism.
            val devices = inventory.adminList(household).map { row ->
                AdminDeviceView(
                    id = row.id,
                    platform = row.platform,
                    name = row.name,
                    lastSeenAt = row.lastSeenAt,
                    revokedAt = row.revokedAt,
                    online = config.registry.online(row.id),
                )
            }
            config.log.event(
                "admin.devices.read",
                mapOf("admin_id" to admin.adminId, "household_id" to household),
            )
            call.respond(HttpStatusCode.OK, devices)
        }

        post("/v1/admin/devices/{id}/diag") {
            val admin = call.adminIdentity(config) ?: return@post
            if (!call.adminLookupsAvailable(config)) return@post
            val rawId = call.parameters["id"].orEmpty()
            val deviceId = rawId
                .takeIf { UUID_PATTERN.matches(it) }
                ?.let { UUID.fromString(it).toString() }
            if (deviceId == null) {
                call.respondError(HttpStatusCode.BadRequest, "E_SCHEMA", "device id must be a uuid")
                return@post
            }
            val request = try {
                call.receiveBounded(DiagRequest.serializer())
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
            val household = request.household
                .takeIf { UUID_PATTERN.matches(it) }
                ?.let { UUID.fromString(it).toString() }
            if (household == null) {
                call.respondError(HttpStatusCode.BadRequest, "E_SCHEMA", "household must be a uuid")
                return@post
            }

            when (
                val result =
                    diag.send(admin.adminId, deviceId, household, request.type, request.body ?: JsonObject(emptyMap()))
            ) {
                is DiagSender.Result.Accepted -> call.respond(
                    HttpStatusCode.Accepted,
                    DiagAccepted(
                        taskId = result.taskId,
                        trace = result.trace,
                        state = result.state,
                    ),
                )

                is DiagSender.Result.Refused -> call.respondError(
                    result.status,
                    result.code,
                    result.message,
                )
            }
        }
    }
}
