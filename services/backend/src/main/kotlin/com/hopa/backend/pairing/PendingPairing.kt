package com.hopa.backend.pairing

import com.hopa.backend.ServerConfig
import com.hopa.backend.auth.DeviceIdentity
import com.hopa.backend.http.BodyTooLarge
import com.hopa.backend.http.RateLimiter
import com.hopa.backend.http.receiveBounded
import com.hopa.backend.http.respondError
import com.hopa.backend.store.Database
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable

/** A new phone or PC asking to be admitted, before any household exists. */
@Serializable
data class PendingRequest(
    val platform: String,
    val name: String,
    val publicKey: String,
    val alg: String,
)

/** The one-time answer to a mint: the raw secret leaves the server exactly here. */
@Serializable
data class PendingMinted(
    val sessionId: String,
    val secret: String,
    val qr: String,
    val expiresAt: String,
)

/** What a poller may learn from a session it holds the secret for. */
@Serializable
data class PendingPairingState(
    val sessionId: String,
    val state: String,
    val name: String,
    val platform: String,
    val publicKey: String,
    val fingerprint: String,
    val expiresAt: String,
)

@Serializable
data class PendingResolveRequest(val sessionSecret: String)

/**
 * A phone shows its approval by wrapping the household content key to the
 * pending device's X25519 key. [wrappedKey] is base64url text at the wire
 * boundary; the server stores the bytes and never unwraps them.
 */
@Serializable
data class VouchRequest(val sessionSecret: String, val wrappedKey: String)

@Serializable
data class Vouched(
    val sessionId: String,
    val householdId: String,
    val memberId: String,
)

/**
 * Pending pairing sessions are minted by the device that wants in and polled by
 * it until an operator vouches and claims. The row is keyed only by the hash of
 * the secret in the QR, so the poller never needs a session id, a household or
 * an account: it presents the secret, the token GUC makes exactly that row
 * visible, and nothing else is stored in the clear.
 *
 * The verbs have separate budgets: minting is rare (5/min per address), polling
 * is the PC/sidecar's liveness loop (30/min per address, enough for every 5 s
 * over the five-minute session), and a vouch is per authenticated device
 * (10/min) so a stolen phone token cannot sweep the pending table.
 */
class PendingPairingService(
    private val db: Database,
    private val clock: () -> Long = System::currentTimeMillis,
    private val publicHost: String =
        System.getenv("HOPA_PUBLIC_HOST") ?: "hopa-backend.onrender.com",
    /**
     * The QR's URL scheme. Public deployments are `wss`; a plain-HTTP LAN run
     * sets `HOPA_PUBLIC_SCHEME=ws` so the phone and the PC can pair without TLS.
     */
    private val publicScheme: String =
        System.getenv("HOPA_PUBLIC_SCHEME") ?: "wss",
    mintPerMinute: Int = 5,
    resolvePerMinute: Int = 30,
    vouchPerMinute: Int = 10,
) {
    init {
        require(publicScheme == "ws" || publicScheme == "wss") {
            "HOPA_PUBLIC_SCHEME must be ws or wss"
        }
    }
    private val mintLimiter = RateLimiter(mintPerMinute, 60_000, clock)
    private val resolveLimiter = RateLimiter(resolvePerMinute, 60_000, clock)
    private val vouchLimiter = RateLimiter(vouchPerMinute, 60_000, clock)

    fun mint(request: PendingRequest, remoteAddress: String): PairingOutcome<PendingMinted> {
        if (!mintLimiter.allow(remoteAddress)) {
            return PairingOutcome.Refused("E_RATE", "too many pending pairings", retryable = true)
        }
        if (request.platform !in PLATFORMS) {
            return PairingOutcome.Refused("E_SCHEMA", "platform must be android or windows")
        }
        if (request.name.isBlank()) {
            return PairingOutcome.Refused("E_SCHEMA", "name must not be blank")
        }
        if (request.name.length > MAX_NAME) {
            return PairingOutcome.Refused("E_SCHEMA", "name is longer than $MAX_NAME characters")
        }
        if (request.alg != "x25519") {
            return PairingOutcome.Refused("E_SCHEMA", "alg must be x25519")
        }
        val publicKey = decodeKey(request.publicKey)
            ?: return PairingOutcome.Refused("E_SCHEMA", "publicKey is not base64")
        if (publicKey.size != 32) {
            return PairingOutcome.Refused("E_SCHEMA", "publicKey must be 32 bytes")
        }

        val sessionId = UUID.randomUUID().toString()
        val secret = Tokens.mint()
        val codeHash = Tokens.hash(secret)
        val hash = Tokens.hex(codeHash)
        val expiresAt = clock() + SESSION_MS

        db.token(hash) { connection ->
            connection.prepareStatement(
                "insert into pending_pairings(id, code_hash, platform, name, public_key, " +
                    "alg, expires_at) values (?::uuid, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setBytes(2, codeHash)
                statement.setString(3, request.platform)
                statement.setString(4, request.name)
                statement.setBytes(5, publicKey)
                statement.setString(6, request.alg)
                statement.setTimestamp(7, Timestamp.from(Instant.ofEpochMilli(expiresAt)))
                statement.executeUpdate()
            }
        }

        // The pending QR names only the secret and the gateway: it carries no
        // household, because none exists until an operator vouches.
        val qr = "hopa://pair?v=1&s=$secret&u=$publicScheme://$publicHost/v1/gateway"
        return PairingOutcome.Ok(
            PendingMinted(
                sessionId = sessionId,
                secret = secret,
                qr = qr,
                expiresAt = Instant.ofEpochMilli(expiresAt).toString(),
            ),
        )
    }

    fun resolve(secret: String, remoteAddress: String): PairingOutcome<PendingPairingState> {
        if (!resolveLimiter.allow(remoteAddress)) {
            return PairingOutcome.Refused("E_RATE", "too many pending pairing polls", retryable = true)
        }
        val hash = Tokens.hex(Tokens.hash(secret))
        val row = db.token(hash) { connection ->
            connection.prepareStatement(
                "select id::text, platform, name, public_key, expires_at, " +
                    "vouched_at, claimed_at from pending_pairings " +
                    "where code_hash = decode(?, 'hex')",
            ).use { statement ->
                statement.setString(1, hash)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        PendingRow(
                            id = rows.getString(1),
                            platform = rows.getString(2),
                            name = rows.getString(3),
                            publicKey = rows.getBytes(4),
                            expiresAt = rows.getTimestamp(5).toInstant().toEpochMilli(),
                            vouched = rows.getTimestamp(6) != null,
                            claimed = rows.getTimestamp(7) != null,
                        )
                    }
                }
            }
        } ?: return PairingOutcome.Refused("E_SCHEMA", "unknown pairing session")

        // claimed wins over expiry: an admission is final. Otherwise an expired
        // session says so even if an operator had vouched for it.
        val state = when {
            row.claimed -> "claimed"
            row.expiresAt <= clock() -> "expired"
            row.vouched -> "vouched"
            else -> "pending"
        }
        return PairingOutcome.Ok(
            PendingPairingState(
                sessionId = row.id,
                state = state,
                name = row.name,
                platform = row.platform,
                publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(row.publicKey),
                fingerprint = fingerprint(row.publicKey),
                expiresAt = Instant.ofEpochMilli(row.expiresAt).toString(),
            ),
        )
    }

    /**
     * Bind a pending session to the caller's household and park the wrap on it.
     *
     * One transaction with both GUCs: the presented hash makes the row visible
     * (the pending row has no household yet), and the tenant branch validates
     * the household the update writes. The CAS refuses an expired, already
     * claimed or already vouched session with the same message, so a caller
     * holding an arbitrary secret cannot tell those apart. No
     * `household_key_wraps` row is written here: there is no device to key it
     * on until the claim.
     */
    fun vouch(identity: DeviceIdentity, request: VouchRequest): PairingOutcome<Vouched> {
        if (!vouchLimiter.allow(identity.deviceId)) {
            return PairingOutcome.Refused("E_RATE", "too many pairing vouches", retryable = true)
        }
        val wrapped = decodeKey(request.wrappedKey)
            ?: return PairingOutcome.Refused("E_SCHEMA", "wrappedKey is not base64")
        if (wrapped.size != WRAP_BYTES) {
            return PairingOutcome.Refused("E_SCHEMA", "wrappedKey must be $WRAP_BYTES bytes")
        }

        val hash = Tokens.hex(Tokens.hash(request.sessionSecret))
        val sessionId = db.tenantAndToken(identity.householdId, hash) { connection ->
            connection.prepareStatement(
                "update pending_pairings set household_id = ?::uuid, member_id = ?::uuid, " +
                    "vouched_at = now(), wrapped_key = ?, voucher_device_id = ?::uuid " +
                    "where code_hash = decode(?, 'hex') and vouched_at is null " +
                    "and claimed_at is null and expires_at > now() returning id::text",
            ).use { statement ->
                statement.setString(1, identity.householdId)
                statement.setString(2, identity.memberId)
                statement.setBytes(3, wrapped)
                statement.setString(4, identity.deviceId)
                statement.setString(5, hash)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        } ?: return PairingOutcome.Refused("E_SCHEMA", UNKNOWN_VOUCH)

        return PairingOutcome.Ok(
            Vouched(
                sessionId = sessionId,
                householdId = identity.householdId,
                memberId = identity.memberId,
            ),
        )
    }

    private fun decodeKey(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private fun fingerprint(publicKey: ByteArray): String =
        Tokens.hex(MessageDigest.getInstance("SHA-256").digest(publicKey)).take(8)

    private data class PendingRow(
        val id: String,
        val platform: String,
        val name: String,
        val publicKey: ByteArray,
        val expiresAt: Long,
        val vouched: Boolean,
        val claimed: Boolean,
    )

    private companion object {
        const val SESSION_MS = 5 * 60 * 1000L
        const val MAX_NAME = 100
        const val WRAP_BYTES = 60
        const val UNKNOWN_VOUCH = "unknown, expired or already vouched pairing session"
        val PLATFORMS = setOf("android", "windows")
    }
}

/**
 * The claim half of an admission, consulted before the operator-minted
 * `pairing_sessions` path. Returns null when the presented secret names no
 * pending row, so the caller falls through unchanged.
 *
 * The CAS, the device, the wrap and the tokens commit together: a race that
 * loses the CAS leaves a retryable session, not a device that can never sign in
 * and not a second wrap. A public key that differs from the minted one is
 * refused before the transaction and leaves the session claimable.
 */
internal fun claimPendingSession(
    db: Database,
    tokens: TokenIssuer,
    clock: () -> Long,
    codeHashHex: String,
    request: ClaimRequest,
    publicKey: ByteArray,
): PairingOutcome<Claimed>? {
    val parked = db.token(codeHashHex) { connection ->
        connection.prepareStatement(
            "select id::text, household_id::text, member_id::text, public_key, " +
                "expires_at, vouched_at, claimed_at, wrapped_key, voucher_device_id::text, " +
                "platform, name, alg from pending_pairings where code_hash = decode(?, 'hex')",
        ).use { statement ->
            statement.setString(1, codeHashHex)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    ParkedRow(
                        id = rows.getString(1),
                        householdId = rows.getString(2),
                        memberId = rows.getString(3),
                        publicKey = rows.getBytes(4),
                        expiresAt = rows.getTimestamp(5).toInstant().toEpochMilli(),
                        vouched = rows.getTimestamp(6) != null,
                        claimed = rows.getTimestamp(7) != null,
                        wrap = rows.getBytes(8),
                        voucherDeviceId = rows.getString(9),
                        platform = rows.getString(10),
                        name = rows.getString(11),
                        alg = rows.getString(12),
                    )
                }
            }
        }
    } ?: return null

    if (parked.claimed) {
        return PairingOutcome.Refused("E_SCHEMA", "pairing session already claimed")
    }
    if (!parked.vouched) {
        return PairingOutcome.Refused("E_SCHEMA", "pairing session has not been vouched")
    }
    if (parked.expiresAt <= clock()) {
        return PairingOutcome.Refused("E_SCHEMA", "pairing session expired")
    }
    if (!parked.publicKey.contentEquals(publicKey)) {
        return PairingOutcome.Refused("E_SCHEMA", "publicKey does not match the pairing session")
    }
    val wrap = parked.wrap
        ?: return PairingOutcome.Refused("E_SCHEMA", "pairing session has no wrapped key")
    val householdId = parked.householdId
        ?: return PairingOutcome.Refused("E_SCHEMA", "pairing session has not been vouched")
    val memberId = parked.memberId
        ?: return PairingOutcome.Refused("E_SCHEMA", "pairing session has not been vouched")

    val device = UUID.randomUUID().toString()
    val issued = try {
        db.tenantAndToken(householdId, codeHashHex) { connection ->
            // R28: the PC needs the voucher's public key to derive the same
            // static-static KEK. Read it in this tenant transaction; a missing
            // device or key makes the session unusable.
            val voucherPublicKey = parked.voucherDeviceId?.let { voucherDeviceId ->
                connection.prepareStatement(
                    "select public_key from devices where id = ?::uuid",
                ).use {
                    it.setString(1, voucherDeviceId)
                    it.executeQuery().use { rows ->
                        if (rows.next()) rows.getBytes(1) else null
                    }
                }
            } ?: throw MissingVoucherKey()
            if (voucherPublicKey.isEmpty()) throw MissingVoucherKey()

            val won = connection.prepareStatement(
                "update pending_pairings set claimed_at = now() " +
                    "where id = ?::uuid and claimed_at is null " +
                    "and vouched_at is not null and expires_at > now()",
            ).use {
                it.setString(1, parked.id)
                it.executeUpdate()
            }
            if (won != 1) throw PendingTaken()

            connection.prepareStatement(
                "insert into devices(id, household_id, member_id, platform, name, " +
                    "public_key, alg, push_token) values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?)",
            ).use {
                it.setString(1, device)
                it.setString(2, householdId)
                it.setString(3, memberId)
                // The minted row is authoritative: a claim cannot rename the
                // device, change its platform or swap its algorithm after the
                // phone saw the QR.
                it.setString(4, parked.platform)
                it.setString(5, parked.name)
                it.setBytes(6, publicKey)
                it.setString(7, parked.alg)
                it.setString(8, request.pushToken)
                it.executeUpdate()
            }

            // The parked ciphertext moves to the device it was wrapped for, in
            // the same transaction that created that device.
            connection.prepareStatement(
                "insert into household_key_wraps(household_id, device_id, wrapped_key) " +
                    "values (?::uuid, ?::uuid, ?)",
            ).use {
                it.setString(1, householdId)
                it.setString(2, device)
                it.setBytes(3, wrap)
                it.executeUpdate()
            }

            ClaimedTokens(
                access = tokens.issueAccessOn(connection, device, householdId),
                refresh = tokens.issueRefreshOn(connection, device, householdId),
                voucherPublicKey = voucherPublicKey,
            )
        }
    } catch (_: PendingTaken) {
        return PairingOutcome.Refused("E_SCHEMA", "pairing session already claimed")
    } catch (_: MissingVoucherKey) {
        return PairingOutcome.Refused("E_SCHEMA", "pairing session has no voucher public key")
    }

    return PairingOutcome.Ok(
        Claimed(
            deviceId = device,
            householdId = householdId,
            accessToken = issued.access.token,
            refreshToken = issued.refresh.token,
            expiresAt = issued.access.expiresAt,
            wrappedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(wrap),
            voucherPublicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(issued.voucherPublicKey),
        ),
    )
}

private data class ParkedRow(
    val id: String,
    val householdId: String?,
    val memberId: String?,
    val publicKey: ByteArray,
    val expiresAt: Long,
    val vouched: Boolean,
    val claimed: Boolean,
    val wrap: ByteArray?,
    val voucherDeviceId: String?,
    val platform: String,
    val name: String,
    val alg: String,
)

private data class ClaimedTokens(
    val access: Issued,
    val refresh: Issued,
    val voucherPublicKey: ByteArray,
)

private class PendingTaken : RuntimeException()

private class MissingVoucherKey : RuntimeException()

fun Application.pendingPairingRoutes(config: ServerConfig) {
    val api = config.pendingPairings ?: return

    routing {
        post("/v1/pair/pending") {
            val request = try {
                call.receiveBounded(PendingRequest.serializer())
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
            when (val outcome = api.mint(request, remoteAddress(call))) {
                is PairingOutcome.Ok -> call.respond(HttpStatusCode.Created, outcome.value)
                is PairingOutcome.Refused -> call.refuse(outcome)
            }
        }

        post("/v1/pair/pending/resolve") {
            val request = try {
                call.receiveBounded(PendingResolveRequest.serializer())
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
            when (val outcome = api.resolve(request.sessionSecret, remoteAddress(call))) {
                is PairingOutcome.Ok -> call.respond(HttpStatusCode.OK, outcome.value)
                is PairingOutcome.Refused -> call.refuse(outcome)
            }
        }

        // A signed-in phone vouches for the pending device. 200, not 201: no new
        // resource is created for the caller, an existing session changes state.
        post("/v1/pair/vouch") {
            val presented = call.request.header(HttpHeaders.Authorization)
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val identity = presented?.let(config.auth::deviceFor)
            if (identity == null) {
                call.respondError(
                    HttpStatusCode.Unauthorized,
                    "E_AUTH",
                    "device token is not valid",
                )
                return@post
            }
            val request = try {
                call.receiveBounded(VouchRequest.serializer())
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
            when (val outcome = api.vouch(identity, request)) {
                is PairingOutcome.Ok -> call.respond(HttpStatusCode.OK, outcome.value)
                is PairingOutcome.Refused -> call.refuse(outcome)
            }
        }
    }
}
