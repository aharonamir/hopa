package com.hopa.feature.execution

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.hopa.feature.execution.ExecutionDependencies
import com.hopa.feature.settings.SettingsViewModel

/** The computer as a surface: address, pairing and the two checks. */
@Composable
fun ComputerRoute(
    onBack: () -> Unit,
    showGatewayPairing: Boolean = true,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ComputerScreen(
        state = state,
        onBack = onBack,
        onSave = viewModel::save,
        onTestBridge = viewModel::testBridge,
        onPairBridge = viewModel::pairBridge,
        onPairBackend = viewModel::pair,
        onDisconnectBackend = viewModel::disconnectBackend,
        onRefreshBackend = viewModel::refresh,
        showGatewayPairing = showGatewayPairing,
    )
}
