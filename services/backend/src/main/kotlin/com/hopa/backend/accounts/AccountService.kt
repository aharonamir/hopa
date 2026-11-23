package com.hopa.backend.accounts

import com.hopa.backend.http.RateLimiter
import com.hopa.backend.pairing.Issued
import com.hopa.backend.pairing.TokenIssuer
import com.hopa.backend.store.Database
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.util.Base64
import java.util.UUID

/** The result of an account operation: a created account, or a uniform refusal. */
sealed interface AccountOutcome<out T> {
    data class Ok<T>(val value: T) : AccountOutcome<T>

    data class Refused(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : AccountOutcome<Nothing>
}

/**
 * Phone + password accounts. The phone is E.164-normalized server-side and is
 * only ever a login lookup: it is never returned, never put in a token and never
 * logged. A wrong phone and a wrong password are the same uniform `E_AUTH`.
 *
 * Every unauthenticated route is rate limited per IP and per phone. Sign-up
 * writes the account, its household of one, the owner member, the phone's
 * device, the household key wrap and the tokens in one tenant transaction.
 */
class AccountService(
    private val db: Database,
    private val tokens: TokenIssuer,
    private val clock: () -> Long = System::currentTimeMillis,
    perMinute: Int = 5,
) {
    private val ipLimiter = RateLimiter(perMinute, 60_000, clock)
    private val phoneLimiter = RateLimiter(perMinute, 60_000, clock)

    private val secureRandom = SecureRandom()

    // A real argon2id encoding, verified only so a missing account pays the same
    // cost as a present one. It is never the caller's password.
    private val dummyHash: String by lazy { Passwords.hash("hopa-dummy-password".toCharArray()) }

    fun signUp(request: SignUpRequest, remoteAddress: String): AccountOutcome<AccountCreated> {
        // The IP budget is spent first, before the phone is even parsed, so a
        // sixth request from one address is E_RATE regardless of the body.
        if (!ipLimiter.allow(remoteAddress)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        val phone = Phones.normalize(request.phone)
            ?: return AccountOutcome.Refused("E_SCHEMA", "invalid phone number")
        if (!phoneLimiter.allow(phone)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        if (request.platform !in PLATFORMS) {
            return AccountOutcome.Refused("E_SCHEMA", "platform must be android or windows")
        }
        validateName(request.name)?.let { return AccountOutcome.Refused("E_SCHEMA", it) }
        if (request.alg != "x25519") {
            return AccountOutcome.Refused("E_SCHEMA", "alg must be x25519")
        }
        val publicKey = decodeKey(request.publicKey)
            ?: return AccountOutcome.Refused("E_SCHEMA", "publicKey is not base64")
        if (publicKey.size != 32) {
            return AccountOutcome.Refused("E_SCHEMA", "publicKey must be 32 bytes")
        }
        val wrapped = decodeKey(request.selfWrappedKey)
            ?: return AccountOutcome.Refused("E_SCHEMA", "selfWrappedKey is not base64")
        if (wrapped.isEmpty() || wrapped.size > MAX_WRAP_BYTES) {
            return AccountOutcome.Refused(
                "E_SCHEMA",
                "selfWrappedKey must be at most $MAX_WRAP_BYTES bytes",
            )
        }
        if (request.pushToken != null && request.pushToken.length > MAX_PUSH_TOKEN) {
            return AccountOutcome.Refused("E_SCHEMA", "pushToken is too long")
        }

        // Any row with this phone -- live or tombstoned -- is a uniform E_AUTH.
        if (phoneExists(phone)) {
            return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)
        }

        val accountId = UUID.randomUUID().toString()
        val householdId = UUID.randomUUID().toString()
        val memberId = UUID.randomUUID().toString()
        val passwordHash = Passwords.hash(request.password.toCharArray())

        // Account, household, owner member, device, wrap and tokens commit in one
        // tenant transaction. A failure anywhere rolls the whole thing back. A
        // concurrent duplicate phone slips past the pre-check above and hits the
        // partial unique index (SQLState 23505) here; that is refused with the
        // same uniform E_AUTH rather than surfacing as a 500.
        val signedIn = try {
            db.tenant(householdId) { connection ->
                createAccountHouseholdAndMember(
                    connection,
                    accountId,
                    householdId,
                    memberId,
                    phone,
                    passwordHash,
                    request.name,
                )
                val device = issueSignedInDevice(
                    connection,
                    accountId,
                    householdId,
                    memberId,
                    request.platform,
                    request.name,
                    publicKey,
                    request.pushToken,
                )
                connection.prepareStatement(
                    "insert into household_key_wraps(household_id, device_id, wrapped_key) " +
                        "values (?::uuid, ?::uuid, ?)",
                ).use {
                    it.setString(1, householdId)
                    it.setString(2, device.deviceId)
                    it.setBytes(3, wrapped)
                    it.executeUpdate()
                }
                device
            }
        } catch (problem: SQLException) {
            if (problem.sqlState == "23505") {
                return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)
            }
            throw problem
        }

        return AccountOutcome.Ok(signedIn.toCreated(accountId, householdId))
    }

    fun signIn(request: SignInRequest, remoteAddress: String): AccountOutcome<AccountCreated> {
        if (!ipLimiter.allow(remoteAddress)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        val phone = Phones.normalize(request.phone)
            ?: return AccountOutcome.Refused("E_SCHEMA", "invalid phone number")
        if (!phoneLimiter.allow(phone)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        if (request.platform !in PLATFORMS) {
            return AccountOutcome.Refused("E_SCHEMA", "platform must be android or windows")
        }
        validateName(request.name)?.let { return AccountOutcome.Refused("E_SCHEMA", it) }
        val publicKey = decodeKey(request.publicKey)
            ?: return AccountOutcome.Refused("E_SCHEMA", "publicKey is not base64")
        if (publicKey.size != 32) {
            return AccountOutcome.Refused("E_SCHEMA", "publicKey must be 32 bytes")
        }
        if (request.pushToken != null && request.pushToken.length > MAX_PUSH_TOKEN) {
            return AccountOutcome.Refused("E_SCHEMA", "pushToken is too long")
        }

        // Look up only live accounts. A missing account pays the same argon2
        // cost as a present one, so a wrong phone and a wrong password are the
        // same uniform E_AUTH (and the same wall-clock shape).
        val account = db.read { connection ->
            connection.prepareStatement(
                "select id::text, password_hash from accounts where phone = ? and deleted_at is null",
            ).use {
                it.setString(1, phone)
                it.executeQuery().use { rows ->
                    if (rows.next()) AccountRow(rows.getString(1), rows.getString(2)) else null
                }
            }
        }
        val passwordOk = Passwords.verify(
            request.password.toCharArray(),
            account?.passwordHash ?: dummyHash,
        )
        if (account == null || !passwordOk) {
            return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)
        }

        // The household of one the account owns. The definer function is the only
        // non-tenant path into members; the phone never leaves the server.
        val location = resolveLocation(phone)
            ?: return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)

        // A signed-in device and its tokens; no wrap, no history.
        val signedIn = db.tenant(location.householdId) { connection ->
            issueSignedInDevice(
                connection,
                account.id,
                location.householdId,
                location.memberId,
                request.platform,
                request.name,
                publicKey,
                request.pushToken,
            )
        }

        return AccountOutcome.Ok(signedIn.toCreated(account.id, location.householdId))
    }

    /**
     * The verified-wins OTP entry point, called only after the caller proved
     * possession of the phone by presenting a valid one-time code. The phone is
     * already normalized.
     *
     * An existing verified account simply signs in. An existing unverified
     * account binds to that same account and stamps `verified_at` (no second
     * account). A `deleted_at` account is refused, never resurrected. A phone
     * with no account is provisioned on the spot: an account already verified,
     * its household of one, the owner member and the device in one transaction,
     * with no content-key wrap.
     */
    internal fun signInOrProvisionViaOtp(
        phone: String,
        platform: String,
        name: String,
        publicKey: ByteArray,
        pushToken: String?,
    ): AccountOutcome<AccountCreated> {
        val account = db.read { connection ->
            connection.prepareStatement(
                "select id::text, verified_at from accounts where phone = ? and deleted_at is null",
            ).use {
                it.setString(1, phone)
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        OtpAccountRow(rows.getString(1), rows.getTimestamp(2) != null)
                    } else {
                        null
                    }
                }
            }
        }

        if (account != null) {
            val location = resolveLocation(phone)
                ?: return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)
            val signedIn = db.tenant(location.householdId) { connection ->
                issueSignedInDevice(
                    connection,
                    account.id,
                    location.householdId,
                    location.memberId,
                    platform,
                    name,
                    publicKey,
                    pushToken,
                    stampVerifiedAt = !account.verified,
                )
            }
            return AccountOutcome.Ok(signedIn.toCreated(account.id, location.householdId))
        }

        // A tombstoned phone must not be resurrected by an OTP login.
        val tombstoned = db.read { connection ->
            connection.prepareStatement(
                "select 1 from accounts where phone = ? and deleted_at is not null limit 1",
            ).use {
                it.setString(1, phone)
                it.executeQuery().use { rows -> rows.next() }
            }
        }
        if (tombstoned) {
            return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)
        }

        val accountId = UUID.randomUUID().toString()
        val householdId = UUID.randomUUID().toString()
        val memberId = UUID.randomUUID().toString()
        // `password_hash` is not null, so store an argon2 hash of a random 32-byte
        // throwaway that is never retained or logged; password sign-in for this
        // account therefore always fails.
        val passwordHash = throwawayPasswordHash()

        val signedIn = try {
            db.tenant(householdId) { connection ->
                createAccountHouseholdAndMember(
                    connection,
                    accountId,
                    householdId,
                    memberId,
                    phone,
                    passwordHash,
                    name,
                    verified = true,
                )
                issueSignedInDevice(
                    connection,
                    accountId,
                    householdId,
                    memberId,
                    platform,
                    name,
                    publicKey,
                    pushToken,
                )
            }
        } catch (problem: SQLException) {
            if (problem.sqlState == "23505") {
                return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)
            }
            throw problem
        }

        return AccountOutcome.Ok(signedIn.toCreated(accountId, householdId))
    }

    /**
     * The seam Task 4 (OTP verify) reuses: given an existing account and the
     * tenant it already belongs to, insert a signed-in device and issue its
     * access and refresh tokens in the caller's transaction. [stampVerifiedAt]
     * stamps `verified_at` on the account when the caller just proved ownership
     * of the phone. No wrap is written here; sign-up writes the wrap itself.
     */
    internal fun issueSignedInDevice(
        connection: Connection,
        accountId: String,
        householdId: String,
        memberId: String,
        platform: String,
        name: String,
        publicKey: ByteArray,
        pushToken: String?,
        stampVerifiedAt: Boolean = false,
    ): SignedInDevice {
        if (stampVerifiedAt) {
            connection.prepareStatement(
                "update accounts set verified_at = now() where id = ?::uuid and verified_at is null",
            ).use {
                it.setString(1, accountId)
                it.executeUpdate()
            }
        }
        val deviceId = UUID.randomUUID().toString()
        connection.prepareStatement(
            "insert into devices(id, household_id, member_id, platform, name, public_key, alg, push_token) " +
                "values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, 'x25519', ?)",
        ).use {
            it.setString(1, deviceId)
            it.setString(2, householdId)
            it.setString(3, memberId)
            it.setString(4, platform)
            it.setString(5, name)
            it.setBytes(6, publicKey)
            it.setString(7, pushToken)
            it.executeUpdate()
        }
        return SignedInDevice(
            deviceId = deviceId,
            access = tokens.issueAccessOn(connection, deviceId, householdId),
            refresh = tokens.issueRefreshOn(connection, deviceId, householdId),
        )
    }

    /**
     * The shared sign-up/provision seam: inserts the account, its household of
     * one and the owner member in the caller's transaction. Sign-up leaves the
     * account unverified; an OTP provisioning marks it verified (`verified_at =
     * now()`).
     */
    internal fun createAccountHouseholdAndMember(
        connection: Connection,
        accountId: String,
        householdId: String,
        memberId: String,
        phone: String,
        passwordHash: String,
        displayName: String,
        verified: Boolean = false,
    ) {
        connection.prepareStatement(
            "insert into accounts(id, phone, password_hash, verified_at) values (?::uuid, ?, ?, " +
                (if (verified) "now()" else "null") + ")",
        ).use {
            it.setString(1, accountId)
            it.setString(2, phone)
            it.setString(3, passwordHash)
            it.executeUpdate()
        }
        connection.prepareStatement("insert into households(id, name) values (?::uuid, ?)").use {
            it.setString(1, householdId)
            it.setString(2, "Home")
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
    }

    private fun phoneExists(phone: String): Boolean =
        db.read { connection ->
            connection.prepareStatement("select 1 from accounts where phone = ? limit 1").use {
                it.setString(1, phone)
                it.executeQuery().use { rows -> rows.next() }
            }
        }

    /**
     * Resolves the account's household of one through `admin_resolve_phone`, which
     * returns the account's first member row by `created_at`. Correct while every
     * account has exactly one household of one; once week-4 invites attach an
     * account to a second household, this must let the device choose a household
     * instead of silently binding to the first member row.
     */
    private fun resolveLocation(phone: String): HouseholdLocation? =
        db.read { connection ->
            connection.prepareStatement(
                "select household_id::text, member_id::text from admin_resolve_phone(?)",
            ).use {
                it.setString(1, phone)
                it.executeQuery().use { rows ->
                    if (rows.next()) HouseholdLocation(rows.getString(1), rows.getString(2)) else null
                }
            }
        }

    private fun throwawayPasswordHash(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Passwords.hash(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray())
    }

    private fun SignedInDevice.toCreated(accountId: String, householdId: String): AccountCreated =
        AccountCreated(
            accountId = accountId,
            deviceId = deviceId,
            householdId = householdId,
            accessToken = access.token,
            refreshToken = refresh.token,
            expiresAt = access.expiresAt,
        )

    private fun validateName(value: String): String? = when {
        value.isBlank() -> "name must not be blank"
        value.length > MAX_NAME -> "name is longer than $MAX_NAME characters"
        else -> null
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

    internal data class SignedInDevice(
        val deviceId: String,
        val access: Issued,
        val refresh: Issued,
    )

    private data class AccountRow(val id: String, val passwordHash: String)

    private data class OtpAccountRow(val id: String, val verified: Boolean)

    private data class HouseholdLocation(val householdId: String, val memberId: String)

    private companion object {
        const val MAX_NAME = 100
        const val MAX_WRAP_BYTES = 1024
        const val MAX_PUSH_TOKEN = 4_096
        const val AUTH_FAILED = "invalid phone or password"
        const val RATE_LIMITED = "too many attempts"
        val PLATFORMS = setOf("android", "windows")
    }
}
