package com.hopa.backend.admin

import com.hopa.backend.log.EventLog
import com.hopa.backend.store.Database
import com.hopa.backend.store.DeviceInventory

/**
 * The console's account eraser. One account owns one household today, so a
 * removal cuts every device off, deletes the household (every tenant table
 * cascades from it) and then deletes the account row itself.
 *
 * Deleting the row rather than tombstoning it is deliberate: `phoneExists`
 * counts tombstones, so a tombstoned number could never sign up again. The
 * operator asked for an erase, and the audit line carries ids only.
 */
class AccountRemoval(
    private val db: Database,
    private val inventory: DeviceInventory,
    private val kick: (String) -> Unit,
    private val log: EventLog,
) {
    sealed interface Outcome {
        data class Removed(val householdId: String, val devices: Int) : Outcome

        data class Refused(val code: String, val message: String) : Outcome
    }

    fun remove(adminId: String, householdId: String, memberId: String): Outcome {
        // The first read is also the tenancy check: RLS hides another
        // household's rows, and a member with no account resolves to null.
        val accountId = db.tenant(householdId) { connection ->
            connection.prepareStatement(
                "select account_id::text from members " +
                    "where id = ?::uuid and household_id = ?::uuid",
            ).use { statement ->
                statement.setString(1, memberId)
                statement.setString(2, householdId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        } ?: return Outcome.Refused("E_NOT_FOUND", "no account behind this member")

        val deviceIds = db.tenant(householdId) { connection ->
            connection.prepareStatement(
                "select id::text from devices where household_id = ?::uuid",
            ).use { statement ->
                statement.setString(1, householdId)
                statement.executeQuery().use { rows ->
                    val ids = mutableListOf<String>()
                    while (rows.next()) ids += rows.getString(1)
                    ids
                }
            }
        }

        // Cut the tokens and the key wrap per device first, then the socket, so
        // nothing is left that could answer a delivery during the delete.
        deviceIds.forEach { deviceId ->
            inventory.revokeDevice(deviceId, householdId)
            kick(deviceId)
        }

        db.tenant(householdId) { connection ->
            connection.prepareStatement("delete from households where id = ?::uuid").use {
                it.setString(1, householdId)
                it.executeUpdate()
            }
        }

        db.read { connection ->
            connection.prepareStatement("delete from accounts where id = ?::uuid").use {
                it.setString(1, accountId)
                it.executeUpdate()
            }
        }

        log.event(
            "admin.account.remove",
            mapOf("admin_id" to adminId, "household_id" to householdId),
        )
        return Outcome.Removed(householdId, deviceIds.size)
    }
}
