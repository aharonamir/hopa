package com.hopa.backend.store

import com.hopa.backend.harness.PostgresHarness
import com.hopa.backend.harness.newDevice
import com.hopa.backend.harness.newHousehold
import com.hopa.backend.harness.seedHousehold
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Schema v2: accounts, household key wraps, admins, admin sessions and pending
 * pairings. The global tables are read without a tenant; the tenant tables keep
 * V1's FORCE RLS posture, and the admin lookups are the only SECURITY DEFINER
 * surface.
 */
class V2SchemaTest {
    private val db = PostgresHarness.appDatabase()

    @Test
    fun `migrations apply and the v2 tables exist`() {
        val owner = PostgresHarness.ownerDatabase()

        assertEquals(0, owner.migrate())
        assertEquals(0, owner.migrate())

        val tables = PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select table_name
                    from information_schema.tables
                    where table_schema = 'public'
                      and table_name in ('accounts', 'household_key_wraps', 'admins',
                                         'admin_sessions', 'pending_pairings')
                    order by table_name
                    """.trimIndent(),
                ).use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }

        assertEquals(
            listOf(
                "accounts",
                "admin_sessions",
                "admins",
                "household_key_wraps",
                "pending_pairings",
            ),
            tables,
        )
    }

    @Test
    fun `accounts admins and admin sessions are global and not row secured`() {
        val account = UUID.randomUUID().toString()
        db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into accounts(id, phone, password_hash) values (?::uuid, ?, 'hash')",
            ).use {
                it.setString(1, account)
                it.setString(2, "+9725" + UUID.randomUUID().toString().replace("-", "").take(8))
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into admins(id, email, password_hash) values " +
                    "(?::uuid, ?, 'hash')",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, "${UUID.randomUUID()}@example.test")
                it.executeUpdate()
            }
        }

        val (accounts, admins) = db.dataSource.connection.use { connection ->
            connection.count("select count(*) from accounts") to
                connection.count("select count(*) from admins")
        }

        assertTrue(accounts >= 1)
        assertTrue(admins >= 1)

        val secured = PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select relname, relrowsecurity, relforcerowsecurity
                    from pg_class
                    where relname in ('accounts', 'admins', 'admin_sessions')
                    order by relname
                    """.trimIndent(),
                ).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(Triple(rows.getString(1), rows.getBoolean(2), rows.getBoolean(3)))
                        }
                    }
                }
            }
        }

        assertEquals(
            listOf(
                Triple("accounts", false, false),
                Triple("admin_sessions", false, false),
                Triple("admins", false, false),
            ),
            secured,
        )
    }

    @Test
    fun `household key wraps are invisible without a tenant`() {
        val home = newHousehold()
        val other = newHousehold()
        val device = newDevice()
        seedHousehold(db, home, device, UUID.randomUUID().toString())
        seedHousehold(db, other, newDevice(), UUID.randomUUID().toString())

        db.tenant(home) { connection ->
            connection.prepareStatement(
                "insert into household_key_wraps(household_id, device_id, wrapped_key) " +
                    "values (?::uuid, ?::uuid, ?)",
            ).use {
                it.setString(1, home)
                it.setString(2, device)
                it.setBytes(3, ByteArray(48) { 3 })
                it.executeUpdate()
            }
        }

        val withoutTenant = db.dataSource.connection.use {
            it.count("select count(*) from household_key_wraps")
        }
        val ownTenant = db.tenant(home) { it.count("select count(*) from household_key_wraps") }
        val otherTenant = db.tenant(other) { it.count("select count(*) from household_key_wraps") }

        assertEquals(0, withoutTenant)
        assertEquals(1, ownTenant)
        assertEquals(0, otherTenant)
    }

    @Test
    fun `pending pairings are minted by token hash and bound by the vouching tenant`() {
        val home = newHousehold()
        val member = UUID.randomUUID().toString()
        val device = newDevice()
        seedHousehold(db, home, device, member)
        val codeHash = ByteArray(32) { 11 }
        val codeHashHex = hex(codeHash)
        val pending = UUID.randomUUID().toString()

        val minted = db.token(codeHashHex) { connection ->
            connection.prepareStatement(
                """
                insert into pending_pairings(id, code_hash, platform, name, public_key, alg, expires_at)
                values (?::uuid, ?, 'windows', 'Studio', ?, 'x25519', now() + interval '5 minutes')
                """.trimIndent(),
            ).use {
                it.setString(1, pending)
                it.setBytes(2, codeHash)
                it.setBytes(3, ByteArray(32) { 5 })
                it.executeUpdate()
            }
        }
        assertEquals(1, minted)

        val withoutTenant = db.dataSource.connection.use {
            it.count("select count(*) from pending_pairings")
        }
        val byToken = db.token(codeHashHex) { it.count("select count(*) from pending_pairings") }
        val byOtherToken = db.token("00".repeat(32)) {
            it.count("select count(*) from pending_pairings")
        }
        assertEquals(0, withoutTenant)
        assertEquals(1, byToken)
        assertEquals(0, byOtherToken)

        // A mint row has no household yet, so a tenant-only transaction must not
        // see or update it: the two policy branches are not interchangeable.
        val tenantOnlyUpdate = db.tenant(home) { connection ->
            connection.prepareStatement(
                "update pending_pairings set household_id = ?::uuid where id = ?::uuid",
            ).use {
                it.setString(1, home)
                it.setString(2, pending)
                it.executeUpdate()
            }
        }
        assertEquals(0, tenantOnlyUpdate)

        val vouched = db.token(codeHashHex) { connection ->
            connection.prepareStatement(
                """
                update pending_pairings
                set household_id = ?::uuid, member_id = ?::uuid, vouched_at = now()
                where id = ?::uuid
                """.trimIndent(),
            ).use {
                it.setString(1, home)
                it.setString(2, member)
                it.setString(3, pending)
                it.executeUpdate()
            }
        }
        assertEquals(1, vouched)

        val boundToTenant = db.tenant(home) { it.count("select count(*) from pending_pairings") }
        val boundToOther = db.tenant(newHousehold()) { it.count("select count(*) from pending_pairings") }
        assertEquals(1, boundToTenant)
        assertEquals(0, boundToOther)
    }

    @Test
    fun `tasks type defaults to task create and from device is nullable`() {
        val home = newHousehold()
        val member = UUID.randomUUID().toString()
        val device = newDevice()
        seedHousehold(db, home, device, member)

        val (type, fromDevice) = db.tenant(home) { connection ->
            connection.prepareStatement(
                """
                insert into tasks(id, household_id, member_id, to_device, message_id,
                                  state, trace_id, body, deadline_at)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid,
                        'queued', ?, ?, now() + interval '10 minutes')
                returning type, from_device
                """.trimIndent(),
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, home)
                it.setString(3, member)
                it.setString(4, device)
                it.setString(5, UUID.randomUUID().toString())
                it.setString(6, "a".repeat(32))
                it.setBytes(7, "{}".toByteArray())
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1) to rows.getString(2)
                }
            }
        }

        assertEquals("task.create", type)
        assertNull(fromDevice)

        val nullable = PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select is_nullable
                from information_schema.columns
                where table_schema = 'public' and table_name = 'tasks'
                  and column_name = 'from_device'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }

        assertEquals("YES", nullable)
    }

    @Test
    fun `members gain a nullable account link`() {
        val account = UUID.randomUUID().toString()
        db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into accounts(id, phone, password_hash) values (?::uuid, ?, 'hash')",
            ).use {
                it.setString(1, account)
                it.setString(2, "+9725" + UUID.randomUUID().toString().replace("-", "").take(8))
                it.executeUpdate()
            }
        }

        val home = newHousehold()
        val member = UUID.randomUUID().toString()
        seedHousehold(db, home, newDevice(), member)
        val linked = db.tenant(home) { connection ->
            connection.prepareStatement(
                "update members set account_id = ?::uuid where id = ?::uuid",
            ).use {
                it.setString(1, account)
                it.setString(2, member)
                it.executeUpdate()
            }
        }

        assertEquals(1, linked)
        val accountId = db.tenant(home) { connection ->
            connection.prepareStatement("select account_id from members where id = ?::uuid").use {
                it.setString(1, member)
                it.executeQuery().use { rows -> rows.next(); rows.getString(1) }
            }
        }
        assertEquals(account, accountId)

        val rejected = assertThrows(SQLException::class.java) {
            db.tenant(home) { connection ->
                connection.prepareStatement(
                    "update members set account_id = ?::uuid where id = ?::uuid",
                ).use {
                    it.setString(1, UUID.randomUUID().toString())
                    it.setString(2, member)
                    it.executeUpdate()
                }
            }
        }
        assertEquals("23503", rejected.sqlState)
    }

    @Test
    fun `a live phone is unique and a tombstoned phone can be registered again`() {
        val phone = "+9725" + UUID.randomUUID().toString().replace("-", "").take(8)

        fun insertAccount(): Int = db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into accounts(id, phone, password_hash) values (?::uuid, ?, 'hash')",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, phone)
                it.executeUpdate()
            }
        }

        assertEquals(1, insertAccount())
        val duplicate = assertThrows(SQLException::class.java) { insertAccount() }
        assertEquals("23505", duplicate.sqlState)

        db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "update accounts set deleted_at = now() where phone = ? and deleted_at is null",
            ).use {
                it.setString(1, phone)
                assertEquals(1, it.executeUpdate())
            }
        }

        assertEquals(1, insertAccount())
    }

    @Test
    fun `admin lookups exist with exact result columns and app execute`() {
        val member = function("admin_resolve_member") ?: fail("admin_resolve_member is missing")
        val phone = function("admin_resolve_phone") ?: fail("admin_resolve_phone is missing")
        val household = function("admin_resolve_household")
            ?: fail("admin_resolve_household is missing")
        val devices = function("admin_list_devices") ?: fail("admin_list_devices is missing")

        for (fn in listOf(member, phone, household, devices)) {
            assertEquals("hopa_admin", fn.owner, "${fn.name} is not owned by hopa_admin")
            assertTrue(fn.definer, "${fn.name} is not SECURITY DEFINER")
            assertTrue(
                fn.config.orEmpty().contains("search_path=public, pg_temp"),
                "${fn.name} has no pinned search_path: ${fn.config}",
            )
            assertTrue(fn.executable, "${fn.name} is not executable by hopa_app")
            assertEquals(0, fn.publicGrants, "${fn.name} is executable through PUBLIC")
        }

        assertEquals(listOf("member:uuid"), inputs(member))
        assertEquals(
            listOf("household_id:uuid", "member_id:uuid", "display_name:text", "account_id:uuid"),
            outputs(member),
        )

        assertEquals(listOf("phone:text"), inputs(phone))
        assertEquals(
            listOf(
                "household_id:uuid",
                "member_id:uuid",
                "display_name:text",
                "account_id:uuid",
            ),
            outputs(phone),
        )

        assertEquals(listOf("household:uuid"), inputs(household))
        assertEquals(
            listOf("household_id:uuid", "member_id:uuid", "display_name:text", "account_id:uuid"),
            outputs(household),
        )

        assertEquals(listOf("household:uuid"), inputs(devices))
        assertEquals(
            listOf(
                "device_id:uuid",
                "platform:text",
                "name:text",
                "last_seen_at:timestamp with time zone",
                "revoked_at:timestamp with time zone",
            ),
            outputs(devices),
        )
    }

    @Test
    fun `admin lookups resolve tenant rows without a tenant`() {
        val home = newHousehold()
        val member = UUID.randomUUID().toString()
        val device = newDevice()
        seedHousehold(db, home, device, member)

        val account = UUID.randomUUID().toString()
        val phone = "+9725" + UUID.randomUUID().toString().replace("-", "").take(8)
        db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into accounts(id, phone, password_hash) values (?::uuid, ?, 'hash')",
            ).use {
                it.setString(1, account)
                it.setString(2, phone)
                it.executeUpdate()
            }
        }
        db.tenant(home) { connection ->
            connection.prepareStatement(
                "update members set account_id = ?::uuid where id = ?::uuid",
            ).use {
                it.setString(1, account)
                it.setString(2, member)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                "update devices set last_seen_at = now() where id = ?::uuid",
            ).use {
                it.setString(1, device)
                assertEquals(1, it.executeUpdate())
            }
        }

        // Every call is hopa_app with no household/token GUC set.
        val resolvedMember = db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select household_id, member_id, display_name " +
                    "from admin_resolve_member(?::uuid)",
            ).use {
                it.setString(1, member)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val row = Triple(
                        rows.getString("household_id"),
                        rows.getString("member_id"),
                        rows.getString("display_name"),
                    )
                    assertFalse(rows.next())
                    row
                }
            }
        }
        assertEquals(Triple(home, member, "Test"), resolvedMember)

        val resolvedHousehold = db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select household_id, member_id, display_name " +
                    "from admin_resolve_household(?::uuid)",
            ).use {
                it.setString(1, home)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val row = Triple(
                        rows.getString("household_id"),
                        rows.getString("member_id"),
                        rows.getString("display_name"),
                    )
                    assertFalse(rows.next())
                    row
                }
            }
        }
        assertEquals(Triple(home, member, "Test"), resolvedHousehold)

        val resolvedDevice = db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select device_id, platform, name, last_seen_at, revoked_at " +
                    "from admin_list_devices(?::uuid)",
            ).use {
                it.setString(1, home)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertNotNull(rows.getString("last_seen_at"))
                    assertNull(rows.getString("revoked_at"))
                    val row = Triple(
                        rows.getString("device_id"),
                        rows.getString("platform"),
                        rows.getString("name"),
                    )
                    assertFalse(rows.next())
                    row
                }
            }
        }
        assertEquals(Triple(device, "android", "Pixel"), resolvedDevice)

        val resolvedPhone = db.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select household_id, member_id, display_name, account_id " +
                    "from admin_resolve_phone(?)",
            ).use {
                it.setString(1, phone)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val row = listOf(
                        rows.getString("household_id"),
                        rows.getString("member_id"),
                        rows.getString("display_name"),
                        rows.getString("account_id"),
                    )
                    assertFalse(rows.next())
                    row
                }
            }
        }
        assertEquals(listOf(home, member, "Test", account), resolvedPhone)

        // The functions bypass the policy; the app role still does not.
        val directMembers = db.dataSource.connection.use {
            it.count("select count(*) from members")
        }
        assertEquals(0, directMembers)

        // The BYPASSRLS owner is NOLOGIN, and neither runtime role may SET ROLE
        // into it. Superuser cannot be checked from these sessions; both roles
        // here are non-superusers.
        val (canLogin, bypassRls) = PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select rolcanlogin, rolbypassrls from pg_roles where rolname = 'hopa_admin'",
                ).use { rows -> rows.next(); rows.getBoolean(1) to rows.getBoolean(2) }
            }
        }
        assertFalse(canLogin)
        assertTrue(bypassRls)

        val appRoles = db.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select pg_has_role(current_user, 'hopa_admin', 'member'), " +
                        "pg_has_role(current_user, 'hopa_admin', 'set')",
                ).use { rows -> rows.next(); rows.getBoolean(1) to rows.getBoolean(2) }
            }
        }
        assertFalse(appRoles.first)
        assertFalse(appRoles.second)

        // Separate connection: hopa_owner has neither membership nor SET after
        // the harness's operator-style revoke.
        val ownerRoles = PostgresHarness.ownerDatabase().dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select pg_has_role(current_user, 'hopa_admin', 'member'), " +
                        "pg_has_role(current_user, 'hopa_admin', 'set')",
                ).use { rows -> rows.next(); rows.getBoolean(1) to rows.getBoolean(2) }
            }
        }
        assertFalse(ownerRoles.first)
        assertFalse(ownerRoles.second)
    }

    @Test
    fun `admin lookups return no row for unknown values`() {
        val unknown = "00000000-0000-0000-0000-000000000000"
        val (byMember, byHousehold, byPhone) = db.dataSource.connection.use { connection ->
            Triple(
                connection.preparedCount(
                    "select count(*) from admin_resolve_member(?::uuid)",
                    unknown,
                ),
                connection.preparedCount(
                    "select count(*) from admin_resolve_household(?::uuid)",
                    unknown,
                ),
                connection.preparedCount(
                    "select count(*) from admin_resolve_phone(?)",
                    "+970000000000",
                ),
            )
        }

        assertEquals(0, byMember)
        assertEquals(0, byHousehold)
        assertEquals(0, byPhone)
    }

    private data class FunctionInfo(
        val name: String,
        val owner: String,
        val definer: Boolean,
        val config: String?,
        val executable: Boolean,
        val publicGrants: Int,
        val args: List<Arg>,
    ) {
        val tableColumns: List<String> by lazy { args.filter { it.mode == "t" }.map { it.name } }
    }

    private data class Arg(val name: String, val type: String, val mode: String)

    private data class Header(
        val definer: Boolean,
        val config: String?,
        val executable: Boolean,
        val owner: String,
        val publicGrants: Int,
    )

    private fun function(name: String): FunctionInfo? =
        PostgresHarness.adminDatabase().dataSource.connection.use { connection ->
            val header = connection.prepareStatement(
                """
                select p.prosecdef,
                       array_to_string(p.proconfig, ','),
                       has_function_privilege('hopa_app', p.oid, 'EXECUTE'),
                       pg_get_userbyid(p.proowner),
                       (select count(*) from aclexplode(p.proacl) a where a.grantee = 0)
                from pg_proc p
                join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public' and p.proname = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        Header(
                            rows.getBoolean(1),
                            rows.getString(2),
                            rows.getBoolean(3),
                            rows.getString(4),
                            rows.getInt(5),
                        )
                    }
                }
            } ?: return@use null

            val args = connection.prepareStatement(
                """
                select a.name, format_type(a.type, null), a.mode
                from pg_proc p
                cross join lateral unnest(p.proargnames, p.proallargtypes, p.proargmodes)
                    with ordinality as a(name, type, mode, ord)
                join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public' and p.proname = ?
                order by a.ord
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(Arg(rows.getString(1), rows.getString(2), rows.getString(3)))
                        }
                    }
                }
            }

            FunctionInfo(
                name,
                header.owner,
                header.definer,
                header.config,
                header.executable,
                header.publicGrants,
                args,
            )
        }

    private fun inputs(fn: FunctionInfo): List<String> =
        fn.args.filter { it.mode == "i" }.map { "${it.name}:${it.type}" }

    private fun outputs(fn: FunctionInfo): List<String> =
        fn.tableColumns.map { column ->
            val type = fn.args.first { it.mode == "t" && it.name == column }.type
            "$column:$type"
        }

    private fun Connection.count(sql: String): Int =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> rows.next(); rows.getInt(1) }
        }

    private fun Connection.preparedCount(sql: String, argument: String): Int =
        prepareStatement(sql).use { statement ->
            statement.setString(1, argument)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
