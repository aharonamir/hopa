package com.hopa.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hopa.ui.settings.SettingsPage
import com.hopa.ui.settings.SettingsPanel
import com.hopa.ui.settings.SettingsSegment
import com.hopa.ui.theme.HopaColor
import com.hopa.inference.choice.ProviderState

/**
 * Settings as one control surface, not a menu: a readiness ring, controls that
 * act where they are, and one-tap fixes for the thing that is missing.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onTone: (String) -> Unit,
    onChattiness: (String) -> Unit,
    onMascot: (Boolean) -> Unit,
    onToggleAlerts: (Boolean) -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val settings = state.settings
    val clipboard = LocalClipboardManager.current
    var query by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf("")
    }
    val searchable = listOf(
        Searchable("Alerts", "notifications, answer ready", Icons.Filled.Notifications,
            { onOpenPage(PAGE_NOTIFICATIONS) }),
        Searchable("Modes", "profiles, everyday", Icons.Filled.Person, onOpenProfiles),
        Searchable("Health check", "self-check, diagnostics", Icons.Filled.Info,
            onOpenSelfCheck),
        Searchable("Privacy", "what left your phone, tokens", Icons.Filled.Lock,
            { onOpenPage(PAGE_PRIVACY) }),
        Searchable("About", "version, licences", Icons.Filled.Info, onOpenAbout),
    )
    val matches = if (query.isBlank()) {
        emptyList()
    } else {
        val needle = query.lowercase().trim()
        searchable.filter {
            it.title.lowercase().contains(needle) || it.keywords.contains(needle)
        }
    }
    val answers = state.providerState == ProviderState.CLOUD ||
        state.providerState == ProviderState.LAN ||
        state.providerState == ProviderState.ON_DEVICE

    SettingsPage(title = "Settings", onBack = onBack, collapseTitle = true) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search settings") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.isNotBlank()) {
            if (matches.isEmpty()) {
                Text("Nothing matches \"$query\".", fontSize = 13.sp, color = HopaColor.TextSecondary)
            } else {
                SettingsGroup {
                    matches.forEachIndexed { index, entry ->
                        if (index > 0) {
                            SettingsDivider()
                        }
                        SettingsRow(entry.title, entry.keywords, entry.icon) {
                            query = ""
                            entry.open()
                        }
                    }
                }
            }
            return@SettingsPage
        }

        SettingsHeader(
            title = "Settings",
            summary = if (answers) {
                "Ready to help."
            } else {
                "Keys and models are set on How HOPA acts."
            },
        )

        if (state.backendDeviceId.isNotBlank() || state.backendPhone.isNotBlank()) {
            SettingsGroup {
                AccountField("Phone", state.backendPhone, clipboard)
                SettingsDivider()
                AccountField("Household", state.backendHouseholdId, clipboard)
                SettingsDivider()
                AccountField("Device", state.backendDeviceId, clipboard)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsPanel(modifier = Modifier.weight(1f)) {
                Text("Tone", fontSize = 13.sp, color = HopaColor.TextSecondary)
                SettingsSegment(
                    options = listOf("friendly" to "Friendly", "direct" to "Direct"),
                    selected = settings.tone,
                    onSelect = onTone,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Length",
                    fontSize = 13.sp,
                    color = HopaColor.TextSecondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                SettingsSegment(
                    options = listOf(
                        "short" to "Short",
                        "balanced" to "Balanced",
                        "detailed" to "Long",
                    ),
                    selected = settings.chattiness,
                    onSelect = onChattiness,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Mascot",
                    fontSize = 13.sp,
                    color = HopaColor.TextSecondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                SettingsSegment(
                    options = listOf("framed" to "Framed", "cutout" to "Cut-out"),
                    selected = if (settings.mascotFramed) "framed" else "cutout",
                    onSelect = { onMascot(it == "framed") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SettingsPanel {
            SettingsSwitchRow("Alerts when an answer is ready", settings.notifyAnswers) {
                onToggleAlerts(it)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickChip("Modes") { onOpenProfiles() }
            QuickChip("Health") { onOpenSelfCheck() }
            QuickChip("Privacy") { onOpenPage(PAGE_PRIVACY) }
            QuickChip("About") { onOpenAbout() }
        }

        state.error?.let { error ->
            Text(error, color = HopaColor.Danger, fontSize = 13.sp)
        }
    }
}

const val PAGE_NOTIFICATIONS = "notifications"
const val PAGE_PRIVACY = "privacy"

/** One identity value with a tap-to-copy action. */
@Composable
private fun AccountField(title: String, value: String, clipboard: ClipboardManager) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = HopaColor.TextSecondary)
            Text(value.ifBlank { "—" }, fontSize = 13.sp, color = HopaColor.TextPrimary)
        }
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(value)) },
            enabled = value.isNotBlank(),
        ) {
            Text("Copy")
        }
    }
}

/** One entry the search box can find: a title, its words, and where it goes. */
private data class Searchable(
    val title: String,
    val keywords: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val open: () -> Unit,
)
