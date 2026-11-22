package com.hopa.app.account

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hopa.app.di.AppContainer
import com.hopa.app.link.push.FirebaseToken
import com.hopa.ui.component.HopaPageHeader
import com.hopa.ui.theme.HopaColor

/**
 * The sign-in / sign-up screen. Phone + password, one busy flag and one error,
 * and a toggle between signing in and creating an account. On success the
 * stored identity is written and [onSignedIn] moves the app to the home shell.
 */
@Composable
fun SignInRoute(
    container: AppContainer,
    gatewayUrl: String,
    onSignedIn: () -> Unit,
) {
    val viewModel = remember {
        AccountViewModel(
            settings = container.settings,
            gatewayUrl = gatewayUrl,
            deviceName = Build.MODEL,
            pushToken = { FirebaseToken.current() },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            onSignedIn()
        }
    }
    SignInScreen(
        state = state,
        onSignIn = viewModel::signIn,
        onSignUp = viewModel::signUp,
    )
}

@Composable
fun SignInScreen(
    state: AccountUiState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var signUp by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = HopaColor.Bg,
        topBar = { HopaPageHeader(title = if (signUp) "Create account" else "Sign in") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Sign in with the phone number the gateway already knows, or create " +
                    "a new account on this phone.",
                fontSize = 13.sp,
                color = HopaColor.TextSecondary,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone, e.g. +972 50 123 4567") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "New account",
                    fontSize = 13.sp,
                    color = HopaColor.TextSecondary,
                )
                Switch(checked = signUp, onCheckedChange = { signUp = it })
            }
            state.error?.let { error ->
                Text(error, color = HopaColor.Danger, fontSize = 13.sp)
            }
            Button(
                onClick = {
                    if (signUp) onSignUp(phone, password) else onSignIn(phone, password)
                },
                enabled = !state.busy && phone.isNotBlank() && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    when {
                        state.busy -> "Working\u2026"
                        signUp -> "Create account"
                        else -> "Sign in"
                    },
                )
            }
        }
    }
}
