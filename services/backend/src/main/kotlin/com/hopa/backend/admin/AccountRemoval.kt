package com.hopa.backend.admin

import com.hopa.backend.log.EventLog
import com.hopa.backend.store.Database
import com.hopa.backend.store.DeviceInventory
import java.sql.SQLException

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
        // nothing is left that could answer a delivery during the delete. A
        // revoke that lands before a failed erase only makes the cut earlier,
        // which is the safe direction.
        deviceIds.forEach { deviceId ->
            inventory.revokeDevice(deviceId, householdId)
            kick(deviceId)
        }

        // Both deletes share one transaction: a household removed without its
        // account would leave a row that `phoneExists` counts forever, and the
        // console could not retry because the lookup key (the household) is
        // already gone. The account row has no policy, so it deletes from
        // inside the tenant transaction like any tenant row.
        val removed = try {
            db.tenant(householdId) { connection ->
                val households = connection
                    .prepareStatement("delete from households where id = ?::uuid")
                    .use { statement ->
                        statement.setString(1, householdId)
                        statement.executeUpdate()
                    }
                if (households == 0) {
                    return@tenant false
                }
                val accounts = connection
                    .prepareStatement("delete from accounts where id = ?::uuid")
                    .use { statement ->
                        statement.setString(1, accountId)
                        statement.executeUpdate()
                    }
                accounts > 0
            }
        } catch (problem: SQLException) {
            // The account still backs another household's member row (the FK has
            // no cascade). Nothing was deleted: the household delete rolled back.
            return Outcome.Refused("E_CONFLICT", "the account is still used by another household")
        }
        if (!removed) {
            return Outcome.Refused("E_NOT_FOUND", "the household or account was already gone")
        }

        log.event(
            "admin.account.remove",
            mapOf("admin_id" to adminId, "household_id" to householdId),
        )
        return Outcome.Removed(householdId, deviceIds.size)
    }
}
