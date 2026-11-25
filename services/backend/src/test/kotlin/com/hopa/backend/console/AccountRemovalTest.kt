package com.hopa.backend.console

import com.hopa.backend.admin.AccountRemoval
import com.hopa.backend.auth.DeviceIdentity
import com.hopa.backend.gateway.DeviceRegistry
import com.hopa.backend.harness.PostgresHarness
import com.hopa.backend.harness.newDevice
import com.hopa.backend.harness.newHousehold
import com.hopa.backend.log.EventLog
import com.hopa.backend.store.PgDevices
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The console's account eraser: what it removes, what it refuses, what it cuts. */
class AccountRemovalTest {
    private val db = PostgresHarness.appDatabase()
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val log = EventLog { name, fields -> events += name to fields }
    private val registry = DeviceRegistry()
    private val removal = AccountRemoval(db, PgDevices(db), registry::kick, log)
    private val random = SecureRandom()
    private val adminId = UUID.randomUUID().toString()

    private data class Fixture(
        val householdId: String,
        val memberId: String,
        val accountId: String,
        val phone: String,
        val deviceId: String,
    )

    @BeforeEach
    fun setUp() {
        events.clear()
    }

    private fun uniquePhone(): String =
        "+9725" + (1..4).joinToString("") { random.nextInt(10).toString() } + "4567"

    private fun seedAccountHousehold(withAccount: Boolean = true): Fixture {
        val householdId = newHousehold()
        val memberId = UUID.randomUUID().toString()
        val accountId = UUID.randomUUID().toString()
        val phone = uniquePhone()
        val deviceId = newDevice()
        if (withAccount) {
            db.read { connection ->
                connection.prepareStatement(
                    "insert into accounts(id, phone, password_hash, verified_at) " +
                        "values (?::uuid, ?, 'hash', now())",
                ).use {
                    it.setString(1, accountId)
                    it.setString(2, phone)
                    it.executeUpdate()
                }
            }
        }
        db.tenant(householdId) { connection ->
            connection.prepareStatement("insert into households(id, name) values (?::uuid, 'Home')").use {
                it.setString(1, householdId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into members(id, household_id, role, display_name, account_id) " +
                    "values (?::uuid, ?::uuid, 'owner', 'Alice', " +
                    (if (withAccount) "?::uuid" else "null") + ")",
            ).use {
                it.setString(1, memberId)
                it.setString(2, householdId)
                if (withAccount) it.setString(3, accountId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into devices(id, household_id, member_id, platform, name) " +
                    "values (?::uuid, ?::uuid, ?::uuid, 'android', 'Pixel')",
            ).use {
                it.setString(1, deviceId)
                it.setString(2, householdId)
                it.setString(3, memberId)
                it.executeUpdate()
            }
        }
        return Fixture(householdId, memberId, accountId, phone, deviceId)
    }

    private fun householdExists(householdId: String): Boolean = db.tenant(householdId) { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from households").use { rows ->
                rows.next()
                rows.getInt(1) > 0
            }
        }
    }

    private fun deviceExists(householdId: String, deviceId: String): Boolean =
        db.tenant(householdId) { connection ->
            connection.prepareStatement("select count(*) from devices where id = ?::uuid").use {
                it.setString(1, deviceId)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1) > 0
                }
            }
        }

    private fun accountExists(accountId: String): Boolean = db.read { connection ->
        connection.prepareStatement("select count(*) from accounts where id = ?::uuid").use {
            it.setString(1, accountId)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1) > 0
            }
        }
    }

    @Test
    fun `removing an account erases its household, devices and account row`() {
        val fixture = seedAccountHousehold()

        val outcome = removal.remove(adminId, fixture.householdId, fixture.memberId)

        assertTrue(outcome is AccountRemoval.Outcome.Removed, outcome.toString())
        assertEquals(1, (outcome as AccountRemoval.Outcome.Removed).devices)
        assertFalse(householdExists(fixture.householdId), "the household must be gone")
        assertFalse(accountExists(fixture.accountId), "the account row must be gone")
        assertFalse(deviceExists(fixture.householdId, fixture.deviceId), "the device must be gone")
        val audited = events.single { it.first == "admin.account.remove" }
        assertEquals(adminId, audited.second["admin_id"])
        assertEquals(fixture.householdId, audited.second["household_id"])
        assertFalse(audited.second.containsKey("phone"), "the phone must not be logged")
    }

    @Test
    fun `a member with no account is refused and nothing is deleted`() {
        val fixture = seedAccountHousehold(withAccount = false)

        val outcome = removal.remove(adminId, fixture.householdId, fixture.memberId)

        assertTrue(outcome is AccountRemoval.Outcome.Refused, outcome.toString())
        assertTrue(householdExists(fixture.householdId), "the household must survive")
        assertTrue(deviceExists(fixture.householdId, fixture.deviceId), "the device must survive")
        assertTrue(events.none { it.first == "admin.account.remove" })
    }

    @Test
    fun `an unknown household is refused`() {
        val outcome = removal.remove(adminId, newHousehold(), UUID.randomUUID().toString())

        assertTrue(outcome is AccountRemoval.Outcome.Refused, outcome.toString())
    }

    @Test
    fun `removal tells the device's live socket to close`() {
        val fixture = seedAccountHousehold()
        val session = DeviceRegistry.Session(
            DeviceIdentity(fixture.deviceId, fixture.householdId, fixture.memberId),
            Channel(Channel.UNLIMITED),
        )
        registry.join(session)
        assertTrue(registry.online(fixture.deviceId))

        removal.remove(adminId, fixture.householdId, fixture.memberId)

        assertTrue(session.closed.isCompleted, "the live socket must be told to close")
    }
}
