package com.hopa.backend.accounts

import com.hopa.backend.http.RateLimiter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The SMS one-time-code state machine, kept apart from [AccountService] so the
 * code lifecycle (mint, TTL, attempts, send budget) is testable in isolation.
 *
 * Codes live in a single process-local map keyed by the phone's SHA-256, never
 * by the raw phone, and the code itself is stored as its SHA-256 and compared
 * in constant time. This is deliberately in-memory, like the other process-local
 * limits: one container runs this service, and a lost code on restart is cheap
 * to re-request.
 *
 * A wrong phone, a wrong code, an expired code and a used code are the same
 * uniform `E_AUTH`, and every failed verify burns one real argon2 verify so the
 * wall-clock shape does not reveal whether the phone has an account.
 */
class OtpService(
    private val accounts: AccountService,
    private val sms: SmsPort,
    private val clock: () -> Long = System::currentTimeMillis,
    perMinute: Int = 5,
    codesPerHour: Int = 3,
) {
    private val ipLimiter = RateLimiter(perMinute, 60_000, clock)
    private val phoneLimiter = RateLimiter(perMinute, 60_000, clock)
    private val sendLimiter = RateLimiter(codesPerHour, 3_600_000, clock)

    private val secureRandom = SecureRandom()

    private val codes = ConcurrentHashMap<String, CodeEntry>()

    // A real argon2id encoding, verified only so a missing code/account pays the
    // same cost as a present one. It is never the caller's code or password.
    private val dummyHash: String by lazy { Passwords.hash(DUMMY_PASSWORD) }

    /** Mints and sends one code; 202 regardless of whether the phone has an account. */
    fun requestCode(request: OtpRequest, remoteAddress: String): AccountOutcome<OtpRequested> {
        if (!ipLimiter.allow(remoteAddress)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        val phone = Phones.normalize(request.phone)
            ?: return AccountOutcome.Refused("E_SCHEMA", "invalid phone number")
        if (!phoneLimiter.allow(phone)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        if (!sendLimiter.allow(phone)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }

        val code = generateCode()
        codes[phoneKey(phone)] = CodeEntry(codeHash(code), attempts = 0, expiresAt = clock() + CODE_TTL_MS)
        sms.send(phone, code)
        return AccountOutcome.Ok(OtpRequested())
    }

    /** Verifies one code and, on success, signs in or provisions the account. */
    fun verify(request: OtpVerifyRequest, remoteAddress: String): AccountOutcome<AccountCreated> {
        if (!ipLimiter.allow(remoteAddress)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        val phone = Phones.normalize(request.phone)
            ?: return AccountOutcome.Refused("E_SCHEMA", "invalid phone number")
        if (!phoneLimiter.allow(phone)) {
            return AccountOutcome.Refused("E_RATE", RATE_LIMITED, retryable = true)
        }
        if (!CODE_PATTERN.matches(request.code)) {
            return AccountOutcome.Refused("E_SCHEMA", "code must be 6 digits")
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

        if (!consumeValidCode(phone, request.code)) {
            burnArgon2()
            return AccountOutcome.Refused("E_AUTH", AUTH_FAILED)
        }

        val outcome = accounts.signInOrProvisionViaOtp(
            phone,
            request.platform,
            request.name,
            publicKey,
            request.pushToken,
        )
        return when (outcome) {
            is AccountOutcome.Refused -> {
                burnArgon2()
                outcome
            }
            is AccountOutcome.Ok -> outcome
        }
    }

    /**
     * Checks and consumes the code for [phone]. Consuming here makes the code
     * single use: it is removed on success, on its third wrong attempt and on
     * expiry. The map mutation is serialized so two concurrent verifies cannot
     * both spend the same code.
     */
    private fun consumeValidCode(phone: String, rawCode: String): Boolean = synchronized(codes) {
        val key = phoneKey(phone)
        val entry = codes[key] ?: return@synchronized false
        if (entry.expiresAt <= clock()) {
            codes.remove(key)
            return@synchronized false
        }
        if (!MessageDigest.isEqual(entry.codeHash, codeHash(rawCode))) {
            val attempts = entry.attempts + 1
            if (attempts >= MAX_ATTEMPTS) {
                codes.remove(key)
            } else {
                codes[key] = entry.copy(attempts = attempts)
            }
            return@synchronized false
        }
        codes.remove(key)
        true
    }

    private fun generateCode(): String =
        secureRandom.nextInt(1_000_000).toString().padStart(CODE_LENGTH, '0')

    private fun phoneKey(phone: String): String = hex(sha256(phone.toByteArray(Charsets.UTF_8)))

    private fun codeHash(code: String): ByteArray = sha256(code.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun burnArgon2() {
        Passwords.verify(DUMMY_PASSWORD, dummyHash)
    }

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

    private data class CodeEntry(
        val codeHash: ByteArray,
        val attempts: Int,
        val expiresAt: Long,
    )

    private companion object {
        val DUMMY_PASSWORD = "hopa-otp-dummy-password".toCharArray()
        val CODE_PATTERN = Regex("^[0-9]{6}$")
        const val CODE_LENGTH = 6
        const val CODE_TTL_MS = 5 * 60 * 1000L
        const val MAX_ATTEMPTS = 3
        const val MAX_NAME = 100
        const val MAX_PUSH_TOKEN = 4_096
        const val AUTH_FAILED = "invalid phone or code"
        const val RATE_LIMITED = "too many attempts"
        val PLATFORMS = setOf("android", "windows")
    }
}
