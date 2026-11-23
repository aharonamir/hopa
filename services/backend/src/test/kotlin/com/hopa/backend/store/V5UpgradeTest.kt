package com.hopa.backend.store

import com.hopa.backend.harness.PostgresHarness
import java.util.UUID
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Schema v5 on an existing database: the member and household resolvers are
 * recreated with an `account_id` column, so a lookup by member or household id
 * can also return the account fields.
 *
 * This exercises the upgrade path the shared-harness migration never does: the
 * harness applies V1-V5 to a fresh database once, at startup, as a
 * briefly-SUPERUSER `hopa_owner`. Here we build a scratch database, apply V1-V4,
 * and then apply V5 on top, asserting the two recreated functions end up owned
 * by `hopa_admin` and return the four documented columns.
 *
 * The non-superuser provisioning path (deploy guide option 2) cannot be driven
 * to a *successful* migration by this harness: both V2 and V5 end with a
 * residual-capability guard that demands the deploy role hold no `SET` and no
 * `ADMIN OPTION` on `hopa_admin`, but the migration's own
 * `revoke hopa_admin from current_user` removes only its own self-grant, not the
 * operator's `WITH ADMIN OPTION` grant (Postgres 16 tracks the grantor). The
 * temporary grant the guard would need survives the migration and trips the
 * guard. That is a pre-existing defect in the migration/provisioning design,
 * out of scope for this wave; the safe-failure test below pins the documented
 * "V5 fails safely on a non-superuser deploy role" behaviour instead.
 */
class V5UpgradeTest {
    private val admin = PostgresHarness.adminDatabase()

    @Test
    fun `v5 applies to an existing v4 database and re-owns both resolvers`() {
        val dbName = newDbName()
        val url = scratchUrl(dbName)
        try {
            createDb(dbName)

            // V1-V4 apply to a fresh database.
            assertEquals(4, migrate(url, "postgres", "postgres", target = "4"), "V1-V4 must apply")

            val beforeMember = functionShape(url, "admin_resolve_member")
            val beforeHousehold = functionShape(url, "admin_resolve_household")
            assertEquals(THREE_COLUMNS, beforeMember.columns, "pre-V5 member resolver has three columns")
            assertEquals(THREE_COLUMNS, beforeHousehold.columns, "pre-V5 household resolver has three columns")
            assertEquals("hopa_admin", beforeMember.owner)

            // V5 alone applies to the existing database.
            assertEquals(1, migrate(url, "postgres", "postgres", target = null), "only V5 must remain")

            val member = functionShape(url, "admin_resolve_member")
            val household = functionShape(url, "admin_resolve_household")
            for (fn in listOf(member, household)) {
                assertEquals("hopa_admin", fn.owner, "${fn.name} is not owned by hopa_admin after V5")
                assertEquals(FOUR_COLUMNS, fn.columns, "${fn.name} does not return the four documented columns")
            }
        } finally {
            dropDb(dbName)
        }
    }

    @Test
    fun `v5 refuses a non-superuser deploy role without provisioning`() {
        val dbName = newDbName()
        val url = scratchUrl(dbName)
        try {
            createDb(dbName)
            assertEquals(4, migrate(url, "postgres", "postgres", target = "4"), "V1-V4 must apply")

            // A deploy role that can read the schema but has no temporary
            // `hopa_admin` membership cannot drop the two definer functions.
            superuser(url) { statement ->
                statement.execute("grant usage on schema public to hopa_owner")
                statement.execute("grant select, insert, update, delete on all tables in schema public to hopa_owner")
            }

            assertThrows(FlywayException::class.java) {
                migrate(url, "hopa_owner", "hopa_owner", target = null)
            }

            // The failed migration left the schema untouched: the resolvers keep
            // their V2-V4 shape and owner.
            val member = functionShape(url, "admin_resolve_member")
            val household = functionShape(url, "admin_resolve_household")
            for (fn in listOf(member, household)) {
                assertEquals("hopa_admin", fn.owner, "${fn.name} owner must be unchanged")
                assertEquals(THREE_COLUMNS, fn.columns, "${fn.name} columns must be unchanged")
            }
        } finally {
            dropDb(dbName)
        }
    }

    private fun newDbName(): String =
        "hopa_v5_upgrade_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun scratchUrl(dbName: String): String =
        admin.dataSource.jdbcUrl.substringBeforeLast("/") + "/$dbName"

    private fun createDb(dbName: String) {
        admin.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create database \"$dbName\"")
            }
        }
    }

    private fun dropDb(dbName: String) {
        admin.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("drop database if exists \"$dbName\" with (force)")
            }
        }
    }

    private fun superuser(url: String, block: (java.sql.Statement) -> Unit) {
        Database.connect(url, "postgres", "postgres").use { superuser ->
            superuser.dataSource.connection.use { connection ->
                connection.createStatement().use(block)
            }
        }
    }

    private fun migrate(url: String, user: String, password: String, target: String?): Int =
        Database.connect(url, user, password).use { db ->
            val configuration = Flyway.configure().dataSource(db.dataSource)
            if (target != null) configuration.target(MigrationVersion.fromVersion(target))
            configuration.load().migrate().migrationsExecuted
        }

    private data class FunctionShape(val name: String, val owner: String, val columns: List<String>)

    private fun functionShape(url: String, name: String): FunctionShape =
        Database.connect(url, "postgres", "postgres").use { superuser ->
            superuser.dataSource.connection.use { connection ->
                val owner = connection.prepareStatement(
                    """
                    select pg_get_userbyid(p.proowner)
                    from pg_proc p
                    join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = 'public' and p.proname = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, name)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "$name is missing" }
                        rows.getString(1)
                    }
                }
                val columns = connection.prepareStatement(
                    """
                    select a.name, format_type(a.type, null)
                    from pg_proc p
                    cross join lateral unnest(p.proargnames, p.proallargtypes, p.proargmodes)
                        with ordinality as a(name, type, mode, ord)
                    join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = 'public' and p.proname = ?
                      and a.mode = 't'
                    order by a.ord
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, name)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add("${rows.getString(1)}:${rows.getString(2)}")
                        }
                    }
                }
                FunctionShape(name, owner, columns)
            }
        }

    private companion object {
        val THREE_COLUMNS = listOf(
            "household_id:uuid",
            "member_id:uuid",
            "display_name:text",
        )
        val FOUR_COLUMNS = listOf(
            "household_id:uuid",
            "member_id:uuid",
            "display_name:text",
            "account_id:uuid",
        )
    }
}
