package com.hopa.backend.admin

import com.hopa.backend.store.Database

/** One device as the console's inventory page shows it. */
data class AdminDeviceRow(
    val deviceId: String,
    val householdId: String,
    val platform: String,
    val name: String,
    val lastSeenAt: String?,
    val revoked: Boolean,
    val phone: String?,
)

/**
 * Every device, across households, for the console. The read goes through
 * `admin_list_all_devices()`, a SECURITY DEFINER function owned by the
 * BYPASSRLS admin role, so the app role never reads another tenant's rows
 * directly and no policy is weakened. The phone is returned in full: the page
 * masks it and only a deliberate reveal renders it.
 */
class AdminDeviceList(private val db: Database) {
    fun list(): List<AdminDeviceRow> = db.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select device_id::text, household_id::text, platform, name, " +
                    "last_seen_at, revoked_at, phone from admin_list_all_devices()",
            ).use { rows ->
                val devices = mutableListOf<AdminDeviceRow>()
                while (rows.next()) {
                    devices += AdminDeviceRow(
                        deviceId = rows.getString(1),
                        householdId = rows.getString(2),
                        platform = rows.getString(3),
                        name = rows.getString(4),
                        lastSeenAt = rows.getTimestamp(5)?.toInstant()?.toString(),
                        revoked = rows.getTimestamp(6) != null,
                        phone = rows.getString(7),
                    )
                }
                devices
            }
        }
    }
}
