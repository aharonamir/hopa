package com.hopa.backend.accounts

import com.hopa.backend.store.Database
import java.util.UUID

/**
 * Seeds the bootstrap admin from environment variables.
 *
 * A no-op unless both [upsertFromEnv] arguments are present and non-blank, so
 * a deployment without `HOPA_ADMIN_EMAIL`/`HOPA_ADMIN_PASSWORD` gets no admin
 * row. The password is hashed with [Passwords] and upserted into the global
 * `admins` table by email: an existing email updates `password_hash`, a new one
 * inserts with `role = 'admin'`. The upsert runs in [Database.read] because
 * `admins` is a global table with no tenant policy. The password and the hash
 * are never logged.
 */
class AdminSeed(private val db: Database) {
    fun upsertFromEnv(email: String?, password: String?) {
        val adminEmail = email ?: return
        if (adminEmail.isBlank()) return
        val adminPassword = password ?: return
        if (adminPassword.isBlank()) return

        val hash = Passwords.hash(adminPassword.toCharArray())

        db.read { connection ->
            connection.prepareStatement(
                """
                insert into admins(id, email, password_hash, role)
                values (?::uuid, ?, ?, 'admin')
                on conflict (email) do update set password_hash = excluded.password_hash
                """.trimIndent(),
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, adminEmail)
                it.setString(3, hash)
                it.executeUpdate()
            }
        }
    }
}
