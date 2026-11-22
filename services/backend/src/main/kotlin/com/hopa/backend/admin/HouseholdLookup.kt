package com.hopa.backend.admin

import com.hopa.backend.accounts.Phones
import com.hopa.backend.log.EventLog
import com.hopa.backend.pairing.Tokens
import com.hopa.backend.store.Database
import kotlinx.serialization.Serializable

/** One lookup row: the household, its owner member, and the account fields. */
@Serializable
data class LookupRow(
    val householdId: String,
    val memberId: String,
    val displayName: String,
    val phone: String? = null,
    val verified: Boolean,
    val accountStatus: String,
)

/**
 * The admin console's household lookup. Every call that resolves a phone number
 * writes exactly one `admin.pii.read` access-log line (carrying `admin_id`,
 * `query_kind` and a 16-hex `query_hash`, never the raw phone or uuid) before
 * any `accounts` read. Tenant rows are reached only through the `hopa_admin`-
 * owned SECURITY DEFINER functions, so the app role needs no tenant GUC.
 */
class HouseholdLookup(
    private val db: Database,
    private val log: EventLog,
) {
    /**
     * Classifies and resolves one query. A phone-normalizable query resolves
     * through `admin_resolve_phone`; a uuid resolves as a member first, then as
     * a household. A blank or malformed query (neither a phone nor a uuid)
     * resolves to an empty list and writes no access line.
     */
    fun lookup(adminId: String, query: String): List<LookupRow> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val phone = Phones.normalize(q)
        if (phone != null) {
            // The phone resolver joins `accounts`, so the access line is written
            // before it runs.
            pii(adminId, "phone", q)
            return resolvePhone(phone)?.let { listOf(enrich(it)) } ?: emptyList()
        }

        if (!UUID_PATTERN.matches(q)) return emptyList()

        // A uuid-shaped query does not know whether it is a member or a
        // household before resolution, so the access line records `query_kind =
        // "uuid"` regardless of which resolver matches. The definer functions
        // read only `members` (never `accounts`), so the line is written once,
        // before any accounts read.
        pii(adminId, "uuid", q)
        resolveMember(q)?.let { resolved -> return listOf(enrich(resolved)) }
        resolveHousehold(q)?.let { resolved -> return listOf(enrich(resolved)) }
        return emptyList()
    }

    private fun resolvePhone(phone: String): Resolved? = resolve(
        "select household_id::text, member_id::text, display_name, account_id::text " +
            "from admin_resolve_phone(?)",
        phone,
    )

    private fun resolveMember(member: String): Resolved? = resolve(
        "select household_id::text, member_id::text, display_name, account_id::text " +
            "from admin_resolve_member(?::uuid)",
        member,
    )

    private fun resolveHousehold(household: String): Resolved? = resolve(
        "select household_id::text, member_id::text, display_name, account_id::text " +
            "from admin_resolve_household(?::uuid)",
        household,
    )

    private fun resolve(sql: String, argument: String): Resolved? =
        db.read { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, argument)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        Resolved(
                            householdId = rows.getString(1),
                            memberId = rows.getString(2),
                            displayName = rows.getString(3),
                            accountId = rows.getString(4),
                        )
                    }
                }
            }
        }

    /**
     * The account read. `accounts` is a global table, so it is read in
     * [Database.read] with no tenant GUC; the phone never comes from a tenant
     * table. A tombstoned account is skipped exactly like `admin_resolve_phone`
     * does: a member whose account has a `deleted_at` renders no phone, no
     * verified state and `accountStatus = "none"`.
     */
    private fun enrich(resolved: Resolved): LookupRow {
        val accountId = resolved.accountId ?: return none(resolved)
        val account = db.read { connection ->
            connection.prepareStatement(
                "select phone, verified_at from accounts where id = ?::uuid and deleted_at is null",
            ).use { statement ->
                statement.setString(1, accountId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) AccountView(rows.getString(1), rows.getTimestamp(2) != null) else null
                }
            }
        } ?: return none(resolved)
        return LookupRow(
            householdId = resolved.householdId,
            memberId = resolved.memberId,
            displayName = resolved.displayName,
            phone = account.phone,
            verified = account.verified,
            accountStatus = if (account.verified) "verified" else "unverified",
        )
    }

    /** No live account behind the member: no phone, no verified state. */
    private fun none(resolved: Resolved): LookupRow = LookupRow(
        householdId = resolved.householdId,
        memberId = resolved.memberId,
        displayName = resolved.displayName,
        phone = null,
        verified = false,
        accountStatus = "none",
    )

    private fun pii(adminId: String, kind: String, query: String) {
        log.event(
            "admin.pii.read",
            mapOf(
                "admin_id" to adminId,
                "query_kind" to kind,
                "query_hash" to Tokens.hex(Tokens.hash(query)).take(16),
            ),
        )
    }

    private data class Resolved(
        val householdId: String,
        val memberId: String,
        val displayName: String,
        val accountId: String?,
    )

    private data class AccountView(val phone: String, val verified: Boolean)
}
