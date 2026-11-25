package com.hopa.app.account

import com.hopa.backend.accounts.Phones
import com.hopa.backend.crypto.KeyWrap
import com.hopa.backend.pairing.AccountClient
import com.hopa.backend.pairing.BackendUrls
import com.hopa.backend.pairing.X25519Keys
import com.hopa.contracts.json.HopaJson
import com.hopa.stores.AppSettings
import com.hopa.stores.SettingsRepository
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sign up posts the normalized phone and a self wrapped key and stores everything`() = runTest(dispatcher) {
        val server = server(MockResponse().setResponseCode(201).setBody(accountResponse()))
        try {
            val settings = FakeSettings()
            val viewModel = AccountViewModel(
                settings = settings,
                gatewayUrl = server.url("/").toString(),
                deviceName = "Pixel",
                accountClient = { AccountClient(it, dispatcher = dispatcher) },
            )

            viewModel.signUp("+972 50-123-4567", "the-password")
            advanceUntilIdle()

            val request = server.takeRequest()
            assertEquals("/v1/accounts", request.path)
            val body = HopaJson.element.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("+972501234567", body["phone"]?.jsonPrimitive?.content)

            val selfWrappedKey = body["selfWrappedKey"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(selfWrappedKey.isNotBlank())
            val wrapped = Base64.getUrlDecoder().decode(selfWrappedKey)
            assertEquals(60, wrapped.size)

            // Unwrap with the stored phone private key; it must equal the stored content key.
            val privateKeyBytes = Base64.getDecoder().decode(settings.backendPrivateKey())
            val publicKeyBytes = X25519Keys.publicKeyBytes(privateKeyBytes)
            val unwrapped = KeyWrap.unwrapByDevice(wrapped, publicKeyBytes, privateKeyBytes)
            val storedContentKey = Base64.getDecoder().decode(settings.backendContentKey())
            assertArrayEquals(storedContentKey, unwrapped)

            assertEquals("at1", settings.backendAccessToken())
            assertEquals("rt1", settings.backendRefreshToken())
            assertEquals("d1", settings.current().backendDeviceId)
            assertEquals("h1", settings.current().backendHouseholdId)
            assertEquals("+972501234567", settings.current().backendPhone)
            assertFalse(
                settings.identityLandedBeforeTokens,
                "the tokens must be stored before the identity wakes the session",
            )
            assertEquals(
                BackendUrls.gatewayUrl(server.url("/").toString()),
                settings.current().backendUrl,
            )
            assertEquals(true, viewModel.state.value.signedIn)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a uniform E_AUTH surfaces as one message with no phone or password`() = runTest(dispatcher) {
        val server = server(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"E_AUTH","message":"invalid phone or password"}"""),
        )
        try {
            val settings = FakeSettings()
            val viewModel = AccountViewModel(
                settings = settings,
                gatewayUrl = server.url("/").toString(),
                accountClient = { AccountClient(it, dispatcher = dispatcher) },
            )

            viewModel.signIn("+972 50-123-4567", "secret-pass")
            advanceUntilIdle()

            assertEquals("Invalid phone or password.", viewModel.state.value.error)
            assertFalse(viewModel.state.value.error!!.contains("972"))
            assertFalse(viewModel.state.value.error!!.contains("secret-pass"))
            assertEquals(false, viewModel.state.value.signedIn)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a rate limited sign in is never shown as unreachable`() = runTest(dispatcher) {
        val server = server(
            MockResponse().setResponseCode(429)
                .setBody("""{"code":"E_RATE","message":"too many attempts"}"""),
        )
        try {
            val settings = FakeSettings()
            val viewModel = AccountViewModel(
                settings = settings,
                gatewayUrl = server.url("/").toString(),
                accountClient = { AccountClient(it, dispatcher = dispatcher) },
            )

            viewModel.signIn("+972501234567", "pw")
            advanceUntilIdle()

            assertEquals("Too many attempts. Try again in a minute.", viewModel.state.value.error)
            assertFalse(viewModel.state.value.error!!.contains("unreachable"))
            assertFalse(viewModel.state.value.error!!.contains("972"))
            assertEquals(false, viewModel.state.value.signedIn)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sign in stores identity and tokens but never a content key`() = runTest(dispatcher) {
        val server = server(MockResponse().setResponseCode(201).setBody(accountResponse()))
        try {
            val settings = FakeSettings()
            val viewModel = AccountViewModel(
                settings = settings,
                gatewayUrl = server.url("/").toString(),
                deviceName = "Pixel",
                accountClient = { AccountClient(it, dispatcher = dispatcher) },
            )

            viewModel.signIn("+972501234567", "the-password")
            advanceUntilIdle()

            assertEquals("/v1/session", server.takeRequest().path)
            assertEquals("", settings.backendContentKey())
            assertEquals("at1", settings.backendAccessToken())
            assertEquals("rt1", settings.backendRefreshToken())
            assertEquals("d1", settings.current().backendDeviceId)
            assertEquals(true, viewModel.state.value.signedIn)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the moved phones normalizer is reachable from the app`() {
        assertEquals("+972501234567", Phones.normalize("00972 50-123-4567"))
        assertEquals("+972501234567", Phones.normalize("+972 50-123-4567"))
        assertNull(Phones.normalize("++972501234567"))
    }

    private fun accountResponse(): String =
        """{"accountId":"a1","deviceId":"d1","householdId":"h1","accessToken":"at1",
           "refreshToken":"rt1","expiresAt":"2026-11-23T00:00:00Z"}""".trimIndent()

    private fun server(vararg responses: MockResponse): MockWebServer = MockWebServer().apply {
        responses.forEach(::enqueue)
        start()
    }

    private class FakeSettings : SettingsRepository {
        private val flow = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = flow
        fun current(): AppSettings = flow.value

        private var access = ""
        private var refresh = ""
        private var privateKey = ""
        private var contentKey = ""

        /** True if the identity landed while a token it needs was still blank. */
        var identityLandedBeforeTokens = false

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            val next = transform(flow.value)
            if (next.backendDeviceId.isNotBlank() && (access.isBlank() || refresh.isBlank())) {
                identityLandedBeforeTokens = true
            }
            flow.value = next
        }

        override suspend fun modelKey(): String = ""
        override suspend fun hasModelKey(): Boolean = false
        override suspend fun setModelKey(value: String) = Unit
        override suspend fun bridgeToken(): String = ""
        override suspend fun setBridgeToken(value: String) = Unit
        override suspend fun bridgeRefresh(): String = ""
        override suspend fun setBridgeRefresh(value: String) = Unit
        override suspend fun backendAccessToken(): String = access
        override suspend fun setBackendAccessToken(value: String) { access = value }
        override suspend fun backendRefreshToken(): String = refresh
        override suspend fun setBackendRefreshToken(value: String) { refresh = value }
        override suspend fun backendPrivateKey(): String = privateKey
        override suspend fun setBackendPrivateKey(value: String) { privateKey = value }
        override suspend fun backendContentKey(): String = contentKey
        override suspend fun setBackendContentKey(value: String) { contentKey = value }

        override suspend fun clearBackend() {
            access = ""
            refresh = ""
            privateKey = ""
            contentKey = ""
            flow.value = flow.value.copy(backendUrl = "", backendDeviceId = "", backendHouseholdId = "")
        }
    }
}
