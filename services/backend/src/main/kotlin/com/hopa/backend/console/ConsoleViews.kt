package com.hopa.backend.console

import kotlinx.html.BODY
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.FormMethod
import kotlinx.html.InputType

/** One device as the console renders it; presence comes from the registry. */
data class ConsoleDevice(
    val id: String,
    val platform: String,
    val name: String,
    val lastSeenAt: String?,
    val revokedAt: String?,
    val online: Boolean,
)

/** One lookup result with its masked phone, queue depth and device list. */
data class ConsoleLookupRow(
    val householdId: String,
    val memberId: String,
    val displayName: String,
    val maskedPhone: String?,
    val verified: Boolean,
    val accountStatus: String,
    val queueDepth: Int?,
    val devices: List<ConsoleDevice>,
)

/**
 * The server-rendered console pages. Every value is emitted as a text node or
 * an attribute — never `unsafe` — so a display name or query containing HTML is
 * escaped. No page reads or renders a task body, task event, turn or chat.
 */
object ConsoleViews {
    fun signIn(error: Boolean): HTML.() -> Unit = page("Hopa console — sign in") {
        h1 { +"Hopa console" }
        if (error) {
            p { +"Email or password is not valid" }
        }
        form(action = "/console/sign-in", method = FormMethod.post) {
            p {
                label { +"Email" }
                input(type = InputType.text, name = "email")
            }
            p {
                label { +"Password" }
                input(type = InputType.password, name = "password")
            }
            p {
                input(type = InputType.submit) { value = "Sign in" }
            }
        }
    }

    fun index(version: String, db: String): HTML.() -> Unit = page("Hopa console") {
        h1 { +"Hopa console" }
        p { +"Version: $version" }
        p { +"Database: $db" }
        p { +"Queue depth: select a household" }
        renderLookupForm()
    }

    fun lookupForm(hint: String): HTML.() -> Unit = page("Hopa console — lookup") {
        h1 { +"Look up a household" }
        renderLookupForm(hint)
    }

    fun lookupError(query: String, message: String): HTML.() -> Unit = page("Hopa console — lookup") {
        h1 { +"Look up a household" }
        p { +"Query: $query" }
        p { +message }
        renderLookupForm()
    }

    fun lookupResults(query: String, rows: List<ConsoleLookupRow>): HTML.() -> Unit =
        page("Hopa console — lookup") {
            h1 { +"Look up a household" }
            p { +"Query: $query" }
            renderLookupForm()
            if (rows.isEmpty()) {
                p { +"No matching household." }
            } else {
                rows.forEach { row -> renderLookupRow(row) }
            }
        }

    fun device(row: ConsoleDevice, household: String): HTML.() -> Unit = page("Hopa console — device") {
        h1 { +"Device ${row.name}" }
        table {
            tbody {
                tr { th { +"Id" }; td { +row.id } }
                tr { th { +"Platform" }; td { +row.platform } }
                tr { th { +"Name" }; td { +row.name } }
                tr { th { +"Last seen" }; td { +(row.lastSeenAt ?: "—") } }
                tr { th { +"Revoked" }; td { +(if (row.revokedAt != null) "yes" else "no") } }
                tr { th { +"Online" }; td { +(if (row.online) "online" else "offline") } }
            }
        }
        h2 { +"Send a diagnostic" }
        form(action = "/console/device/${row.id}/diag", method = FormMethod.post) {
            p {
                label { +"Type" }
                select {
                    name = "type"
                    option { value = "diag.ping"; +"Ping" }
                    option { value = "diag.state"; +"State" }
                    option { value = "diag.reset"; +"Reset" }
                }
            }
            input(type = InputType.hidden, name = "household") { value = household }
            p {
                input(type = InputType.submit) { value = "Send" }
            }
        }
    }

    fun diagAccepted(taskId: String, trace: String, state: String, deviceId: String): HTML.() -> Unit =
        page("Hopa console — diagnostic") {
            h1 { +"Diagnostic accepted" }
            p { +"Task id: $taskId" }
            p { +"Trace: $trace" }
            p { +"State: $state" }
            p { +"Device: $deviceId" }
        }

    fun error(status: Int, message: String): HTML.() -> Unit = page("Hopa console") {
        h1 { +"Console error ($status)" }
        p { +message }
    }

    private fun page(titleText: String, bodyBlock: BODY.() -> Unit): HTML.() -> Unit = {
        head { title { +titleText } }
        body { bodyBlock() }
    }

    private fun BODY.renderLookupForm(hint: String? = null) {
        h2 { +"Look up a household" }
        if (hint != null) {
            p { +hint }
        }
        form(action = "/console/lookup", method = FormMethod.get) {
            p {
                label { +"Query (phone, member id or household id)" }
                input(type = InputType.text, name = "query")
            }
            p {
                input(type = InputType.submit) { value = "Look up" }
            }
        }
    }

    private fun BODY.renderLookupRow(row: ConsoleLookupRow) {
        h2 { +"Household" }
        table {
            tbody {
                tr { th { +"Household id" }; td { +row.householdId } }
                tr { th { +"Member id" }; td { +row.memberId } }
                tr { th { +"Display name" }; td { +row.displayName } }
                tr { th { +"Phone" }; td { +(row.maskedPhone ?: "—") } }
                tr { th { +"Verified" }; td { +row.verified.toString() } }
                tr { th { +"Status" }; td { +row.accountStatus } }
                tr { th { +"Queue depth" }; td { +(row.queueDepth?.toString() ?: "—") } }
            }
        }
        h3 { +"Devices" }
        if (row.devices.isEmpty()) {
            p { +"No devices." }
        } else {
            ul {
                row.devices.forEach { device ->
                    li {
                        +"${device.name} (${device.platform}) — "
                        +(if (device.online) "online" else "offline")
                        +" "
                        a(href = "/console/device/${device.id}?household=${row.householdId}") { +"details" }
                    }
                }
            }
        }
        val masked = row.maskedPhone
        if (masked != null && row.accountStatus != "none") {
            h3 { +"Remove account" }
            p {
                +("Deletes the household, its devices, its tokens and the account. " +
                    "The phone number can register again afterwards.")
            }
            form(action = "/console/account/remove", method = FormMethod.post) {
                input(type = InputType.hidden, name = "household") { value = row.householdId }
                input(type = InputType.hidden, name = "member") { value = row.memberId }
                p {
                    label { +"Type the last four digits ($masked) to confirm" }
                    input(type = InputType.text, name = "confirm")
                }
                p { input(type = InputType.submit) { value = "Remove account" } }
            }
        }
    }

    /** The confirmation page after an account and its household are gone. */
    fun removed(householdId: String, devices: Int): HTML.() -> Unit =
        page("Hopa console - account removed") {
            h1 { +"Account removed" }
            p { +"Household $householdId and its $devices device(s) are gone." }
            p { a(href = "/console") { +"Back to the console" } }
        }
}
