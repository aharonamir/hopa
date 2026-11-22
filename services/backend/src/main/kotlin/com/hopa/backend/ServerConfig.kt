package com.hopa.backend

import com.hopa.backend.accounts.AccountService
import com.hopa.backend.accounts.OtpService
import com.hopa.backend.accounts.SmsPort
import com.hopa.backend.admin.AdminAuth
import com.hopa.backend.admin.AdminSessions
import com.hopa.backend.admin.HouseholdLookup
import com.hopa.backend.auth.AuthPort
import com.hopa.backend.gateway.DeviceRegistry
import com.hopa.backend.http.RateLimiter
import com.hopa.backend.log.EventLog
import com.hopa.backend.pairing.PairingApi
import com.hopa.backend.pairing.PendingPairingService
import com.hopa.backend.pairing.TokenIssuer
import com.hopa.backend.push.PushPort
import com.hopa.backend.store.DbProbe
import com.hopa.backend.tasks.DeviceDirectory
import com.hopa.backend.tasks.InMemoryTaskStore
import com.hopa.backend.tasks.TaskStore
import com.hopa.backend.trace.TraceQuery
import com.hopa.backend.trace.TraceRecorder

/**
 * Everything the application needs that comes from outside a test: the build
 * version, the auth port, the device directory and the database probe. Secrets
 * are read from the environment in [main], never defaulted here.
 */
data class ServerConfig(
    val version: String,
    val auth: AuthPort = AuthPort.DenyAll,
    /** Admin console tokens; absent when this deployment has no database. */
    val adminAuth: AdminAuth? = null,
    /** True only when the admin lookup functions were provisioned on this database. */
    val adminLookups: Boolean = true,
    /** The admin sign-in issuer; absent without a database. */
    val adminSessions: AdminSessions? = null,
    /** The admin household lookup; absent without a database. */
    val householdLookup: HouseholdLookup? = null,
    val devices: DeviceDirectory = DeviceDirectory.Empty,
    /** The one in-memory presence registry shared by the gateway and device routes. */
    val registry: DeviceRegistry = DeviceRegistry(),
    val db: DbProbe = DbProbe.Unconfigured,
    val pairing: PairingApi? = null,
    val pendingPairings: PendingPairingService? = null,
    val accounts: AccountService? = null,
    /** Master switch for the SMS OTP routes; false by default (no SMS provider). */
    val smsEnabled: Boolean = false,
    /** The one SMS transport, provided only when [smsEnabled] is true. */
    val sms: SmsPort? = null,
    /** The OTP state machine, constructed only when [smsEnabled] and accounts exist. */
    val otp: OtpService? = null,
    val bootstrapToken: String? = null,
    val tokenIssuer: TokenIssuer? = null,
    val tasks: TaskStore = InMemoryTaskStore(),
    val messageLimit: RateLimiter = RateLimiter(20, 1_000),
    val log: EventLog = EventLog.None,
    val push: PushPort = PushPort.None,
    val traces: TraceRecorder = TraceRecorder.None,
    val traceQuery: TraceQuery? = null,
    /** Where the served model files and models.v1.json live; blank serves an empty manifest. */
    val modelsDir: String = "",
)
