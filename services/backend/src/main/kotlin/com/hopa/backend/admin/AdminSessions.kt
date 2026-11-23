package com.hopa.backend.admin

import com.hopa.backend.accounts.Passwords
import com.hopa.backend.http.RateLimiter
import com.hopa.backend.pairing.Tokens
import com.hopa.backend.store.Database
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** An admin console session: the opaque token and its expiry. */
@Serializable
data class AdminSession(val token: String, val expiresAt: String)

/** The result of an admin sign-in: a session, or a uniform refusal. */
sealed interface AdminSessionOutcome<out T> {
    data class Ok<T>(val value: T) : AdminSessionOutcome<T>

    data class Refused(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : AdminSessionOutcome<Nothing>
}

/**
 * Admin console sign-in. An unknown email and a wrong password answer the same
 * uniform `E_AUTH`, and the missing-email branch still verifies a fixed dummy
 * argon2 hash so the two cases cost the same. Rate limited per IP and per email
 * (the sixth attempt in a minute is `E_RATE`). On success the opaque token is
 * stored only as SHA-256 (`decode(?, 'hex')`, the same convention [AdminAuth]
 * reads) with an 8-hour expiry; neither the password, the hash nor the token is
 * ever logged.
 */
class AdminSessions(
    private val db: Database,
    private val clock: () -> Long = System::currentTimeMillis,
    perMinute: Int = 5,
) {
    private val ipLimiter = RateLimiter(perMinute, 60_000, clock)
    private val emailLimiter = RateLimiter(perMinute, 60_000, clock)

    // A real argon2id encoding, verified only so a missing email pays the same
    // cost as a present one. It is never the caller's password.
    private val dummyHash: String by lazy { Passwords.hash("hopa-admin-dummy-password".toCharArray()) }

    fun signIn(email: String, password: String, remoteAddress: String): AdminSessionOutcome<AdminSession> {
        if (!ipLimiter.allow(remoteAddress)) {
            return AdminSessionOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isEmpty()) {
            return AdminSessionOutcome.Refused("E_SCHEMA", "email must not be blank")
        }
        if (!emailLimiter.allow(normalizedEmail)) {
            return AdminSessionOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }

        val admin = db.read { connection ->
            connection.prepareStatement(
                "select id::text, password_hash from admins where email = ?",
            ).use {
                it.setString(1, normalizedEmail)
                it.executeQuery().use { rows ->
                    if (rows.next()) AdminRow(rows.getString(1), rows.getString(2)) else null
                }
            }
        }
        val passwordOk = Passwords.verify(
            password.toCharArray(),
            admin?.passwordHash ?: dummyHash,
        )
        if (admin == null || !passwordOk) {
            return AdminSessionOutcome.Refused("E_AUTH", AUTH_FAILED)
        }

        val token = Tokens.mint()
        val expiresAt = Instant.ofEpochMilli(clock()).plusMillis(SESSION_MS)
        db.read { connection ->
            connection.prepareStatement(
                "insert into admin_sessions(id, admin_id, token_hash, expires_at) " +
                    "values (?::uuid, ?::uuid, decode(?, 'hex'), ?)",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, admin.id)
                it.setString(3, Tokens.hex(Tokens.hash(token)))
                it.setTimestamp(4, Timestamp.from(expiresAt))
                it.executeUpdate()
            }
        }

        return AdminSessionOutcome.Ok(AdminSession(token = token, expiresAt = expiresAt.toString()))
    }

    private data class AdminRow(val id: String, val passwordHash: String)

    private companion object {
        const val SESSION_MS = 8 * 60 * 60 * 1000L
        const val AUTH_FAILED = "invalid email or password"
        const val RATE_LIMITED = "too many attempts"
    }
}
