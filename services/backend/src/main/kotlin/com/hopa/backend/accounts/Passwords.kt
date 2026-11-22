package com.hopa.backend.accounts

import com.hopa.backend.crypto.Argon2
import java.security.SecureRandom

/**
 * Password hashing for the server. [hash] mints its own fresh 16-byte salt
 * with a [SecureRandom] and delegates to the shared [Argon2] primitive; the
 * returned string is the `argon2id$...` encoding, never the raw hash. [verify]
 * is constant-time and never throws. Neither the password nor the hash is
 * logged anywhere here.
 */
object Passwords {
    private const val SALT_BYTES = 16

    private val secureRandom = SecureRandom()

    fun hash(password: CharArray): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        return Argon2.hashPassword(password, salt)
    }

    fun verify(password: CharArray, encoded: String): Boolean =
        Argon2.verifyPassword(password, encoded)
}
