package com.hopa.backend.admin

import com.hopa.backend.ServerConfig
import com.hopa.backend.gateway.DeviceRegistry
import com.hopa.backend.log.EventLog
import com.hopa.backend.push.PushPort
import com.hopa.backend.tasks.DeviceDirectory
import com.hopa.backend.tasks.RouteResult
import com.hopa.backend.tasks.TaskRouter
import com.hopa.backend.tasks.TaskStore
import com.hopa.backend.trace.TraceRecorder
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject

/**
 * The admin diagnostic sender shared by the JSON route and the console form.
 * The device lookup is also the tenancy check: it only sees the named household
 * and hides a revoked device, so both answer the same refusal and no row is
 * written. Comparing the returned household is a second, independent guard
 * against a directory implementation drifting from that contract.
 */
class DiagSender(
    private val devices: DeviceDirectory,
    private val tasks: TaskStore,
    private val registry: DeviceRegistry,
    private val log: EventLog,
    private val push: PushPort,
    private val traces: TraceRecorder,
) {
    sealed interface Result {
        data class Accepted(val taskId: String, val trace: String, val state: String) : Result

        data class Refused(
            val status: HttpStatusCode,
            val code: String,
            val message: String,
        ) : Result
    }

    fun send(
        adminId: String,
        deviceId: String,
        household: String,
        type: String,
        body: JsonObject,
    ): Result {
        val target = devices.lookup(deviceId, household)
        if (target == null || target.householdId != household) {
            return Result.Refused(
                HttpStatusCode.Forbidden,
                "E_TENANT",
                "no such live device in this household",
            )
        }

        val router = TaskRouter(
            store = tasks,
            registry = registry,
            devices = devices,
            log = log,
            push = push,
            traces = traces,
        )
        return when (val routed = router.createServerTask(adminId, target, type, body)) {
            is RouteResult.Accepted ->
                Result.Accepted(taskId = routed.task.id, trace = routed.task.trace, state = routed.task.state)

            is RouteResult.Refused ->
                Result.Refused(HttpStatusCode.BadRequest, routed.error.code, routed.error.message)
        }
    }

    companion object {
        fun from(config: ServerConfig): DiagSender = DiagSender(
            devices = config.devices,
            tasks = config.tasks,
            registry = config.registry,
            log = config.log,
            push = config.push,
            traces = config.traces,
        )
    }
}
