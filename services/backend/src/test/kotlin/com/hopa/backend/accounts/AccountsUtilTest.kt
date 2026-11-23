package com.hopa.backend.accounts

import com.hopa.backend.harness.PostgresHarness
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * E.164 normalization, argon2 password hashing and the bootstrap admin seed.
 *
 * Seed assertions count rows and verify hashes but never put a hash in an
 * assertion message: a failure must not leak a password hash into the log.
 */
class AccountsUtilTest {
    private val db = PostgresHarness.appDatabase()

    @Test
    fun `phones accept a plus leading double zero and separators`() {
        assertEquals("+972501234567", Phones.normalize("+972 50-123-4567"))
        assertEquals("+972501234567", Phones.normalize("972501234567"))
        assertEquals("+972501234567", Phones.normalize("00972501234567"))
    }

    @Test
    fun `phones reject letters short digit runs a double plus and null`() {
        assertNull(Phones.normalize("abc"))
        assertNull(Phones.normalize("12345"))
        assertNull(Phones.normalize("+1234567"))
        assertNull(Phones.normalize("++972501234567"))
        assertNull(Phones.normalize(""))
        assertNull(Phones.normalize(null))
    }

    @Test
    fun `phones reject non ASCII digits`() {
        // Arabic-Indic and Devanagari digits are Unicode digits but are not
        // valid E.164; only ASCII '0'..'9' count as a phone number.
        assertNull(Phones.normalize("\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668"))
        assertNull(Phones.normalize("\u0967\u0968\u0969\u096A\u096B\u096C\u096D\u096E"))
        assertNull(Phones.normalize("\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18"))
    }

    @Test
    fun `passwords round trip through hash and verify`() {
        val password = "correct horse battery staple".toCharArray()
        val encoded = Passwords.hash(password)

        assertTrue(Passwords.verify(password, encoded))
    }

    @Test
    fun `passwords reject a wrong password`() {
        val encoded = Passwords.hash("the right password".toCharArray())

        assertFalse(Passwords.verify("the wrong password".toCharArray(), encoded))
    }

    @Test
    fun `passwords reject a malformed encoded string`() {
        assertFalse(Passwords.verify("anything".toCharArray(), "not-an-argon2-hash"))
    }

    @Test
    fun `admin seed inserts then updates the same row on a second upsert`() {
        val email = "seed-${UUID.randomUUID()}@hopa.test"
        val first = "first-password"
        val second = "second-password"

        AdminSeed(db).upsertFromEnv(email, first)
        assertEquals(1, adminCount(email), "the first upsert must insert one admin row")

        AdminSeed(db).upsertFromEnv(email, second)

        assertEquals(1, adminCount(email), "the second upsert must update the same row, not add one")
        val hash = passwordHash(email)
        assertTrue(Passwords.verify(second.toCharArray(), hash), "the new password must verify after the update")
        assertFalse(Passwords.verify(first.toCharArray(), hash), "the old password must no longer verify after the update")
    }

    @Test
    fun `admin seed is a no op when either env value is blank`() {
        val email = "seed-${UUID.randomUUID()}@hopa.test"

        AdminSeed(db).upsertFromEnv(null, "password")
        AdminSeed(db).upsertFromEnv(email, null)
        AdminSeed(db).upsertFromEnv("   ", "password")
        AdminSeed(db).upsertFromEnv(email, "")

        assertEquals(0, adminCount(email), "a blank env value must leave the table untouched")
    }

    private fun adminCount(email: String): Int =
        db.read { connection ->
            connection.prepareStatement("select count(*) from admins where email = ?").use {
                it.setString(1, email)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }

    private fun passwordHash(email: String): String =
        db.read { connection ->
            connection.prepareStatement("select password_hash from admins where email = ?").use {
                it.setString(1, email)
                it.executeQuery().use { rows -> rows.next(); rows.getString(1) }
            }
        }
}
