package com.hopa.app.link.session

import com.hopa.app.link.session.SessionFixtures.claimResponse
import com.hopa.app.link.session.SessionFixtures.FakeLink
import com.hopa.app.link.session.SessionFixtures.FakeSettings
import com.hopa.app.link.session.SessionFixtures.gateway
import com.hopa.app.link.session.SessionFixtures.pairingServer
import com.hopa.app.link.session.SessionFixtures.session
import com.hopa.backend.pairing.BackendPairing
import com.hopa.backend.pairing.PairingLink
import com.hopa.backend.wire.BackendEnvelope
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.hopa.app.link.push.FirebaseToken

class BackendSessionTest {
    @Test
    fun `pairing stores the credentials and reports the device`() = runBlocking {
        val server = pairingServer(MockResponse().setResponseCode(201).setBody(claimResponse()))
        try {
            val settings = FakeSettings()
            val session = session(settings)

            val result = session.pair(PairingLink("h1", "s1", gateway(server)))

            assertTrue(result.isSuccess)
            assertEquals("d1", settings.snapshot.backendDeviceId)
            assertEquals("h1", settings.snapshot.backendHouseholdId)
            assertEquals("a1", settings.backendAccessToken())
            assertEquals("r1", settings.backendRefreshToken())
            assertTrue(settings.backendPrivateKey().isNotBlank())
            assertEquals("d1", session.deviceId)
            assertEquals(gateway(server), session.gatewayUrl)

            val request = server.takeRequest()
            assertEquals("/v1/pair/claim", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("Xiaomi", body["name"]!!.jsonPrimitive.content)
            val publicKey = body["publicKey"]!!.jsonPrimitive.content
            assertFalse(publicKey.contains('='))
            assertFalse(publicKey.contains('+'))
            assertFalse(publicKey.contains('/'))
            assertEquals(32, Base64.getUrlDecoder().decode(publicKey).size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a failed pair leaves the old identity alone`() = runBlocking {
        val server = pairingServer(
            MockResponse().setResponseCode(500)
                .setBody("""{"code":"E_SESSION","message":"expired"}"""),
        )
        try {
            val settings = FakeSettings().apply {
                seed(
                    url = "wss://old/v1/gateway",
                    device = "old-device",
                    household = "old-household",
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                    privateKey = "old-key",
                )
            }
            val session = session(settings)

            val result = session.pair(PairingLink("h1", "s1", gateway(server)))

            assertTrue(result.isFailure)
            assertEquals("old-device", settings.snapshot.backendDeviceId)
            assertEquals("old-household", settings.snapshot.backendHouseholdId)
            assertEquals("wss://old/v1/gateway", settings.snapshot.backendUrl)
            assertEquals("old-access", settings.backendAccessToken())
            assertEquals("old-refresh", settings.backendRefreshToken())
            assertEquals("old-key", settings.backendPrivateKey())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `foreground opens exactly one link and disconnect stops it`() = runBlocking {
        val settings = FakeSettings().paired("wss://x/v1/gateway")
        val created = CompletableDeferred<FakeLink>()
        val builds = AtomicInteger()
        val session = session(settings, linkFactory = { credentials, _, _, pushToken, refresh ->
            builds.incrementAndGet()
            FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
        })

        FirebaseToken.set("tok")
        try {
            session.onForeground()
            val link = withTimeout(2_000) { created.await() }
            session.onForeground()
            delay(100)

            assertEquals(1, builds.get())
            assertEquals("d1", link.credentials.deviceId)
            assertEquals("tok", link.pushToken())
            assertTrue(link.started)

            session.disconnect()
            assertTrue(link.stopped)
        } finally {
            FirebaseToken.set(null)
            session.disconnect()
        }
    }

    @Test
    fun `a second wake reuses the open link`() = runBlocking {
        val settings = FakeSettings().paired("wss://x/v1/gateway")
        val created = CompletableDeferred<FakeLink>()
        val builds = AtomicInteger()
        val session = session(settings, linkFactory = { credentials, _, _, pushToken, refresh ->
            builds.incrementAndGet()
            FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            session.wake(scope)
            withTimeout(2_000) { created.await() }
            session.wake(scope)
            delay(100)

            assertEquals(1, builds.get())
        } finally {
            session.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun `refresh keeps the stored identity and persists the new tokens`() = runBlocking {
        val server = pairingServer(
            MockResponse().setResponseCode(201).setBody(claimResponse()),
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"a2","refreshToken":"r2","expiresAt":"2026-11-17T00:15:00Z"}""",
            ),
        )
        try {
            val settings = FakeSettings()
            val created = CompletableDeferred<FakeLink>()
            val session = session(
                settings,
                linkFactory = { credentials, _, _, pushToken, refresh ->
                    FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
                },
            )
            session.pair(PairingLink("h1", "s1", gateway(server)))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                session.wake(scope)
                val link = withTimeout(2_000) { created.await() }

                val refreshed = link.refresh()

                assertNotNull(refreshed)
                assertEquals("d1", refreshed!!.deviceId)
                assertEquals("h1", refreshed.householdId)
                assertEquals("a2", settings.backendAccessToken())
                assertEquals("r2", settings.backendRefreshToken())
            } finally {
                session.disconnect()
                scope.cancel()
            }
        } finally {
            server.shutdown()
        }
    }
    @Test
    fun `disconnect waits for a run before it closes the link`() = runBlocking {
        val settings = FakeSettings().paired("wss://x/v1/gateway")
        val created = CompletableDeferred<FakeLink>()
        val session = session(settings, linkFactory = { credentials, _, _, pushToken, refresh ->
            FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
        })
        try {
            session.onForeground()
            val link = withTimeout(2_000) { created.await() }
            session.taskStarted()

            session.disconnect()
            delay(100)

            assertFalse(link.stopped)

            session.taskFinished()
            withTimeout(2_000) { while (!link.stopped) delay(20) }
            assertTrue(link.stopped)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `a delivered diagnostic reaches the app handler and is acked once`() = runBlocking {
        val settings = FakeSettings().paired("wss://x/v1/gateway")
        val created = CompletableDeferred<FakeLink>()
        val session = session(settings, linkFactory = { credentials, _, _, pushToken, refresh ->
            FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
        })
        val handled = CompletableDeferred<BackendEnvelope>()
        session.onDiagnostic = { frame -> handled.complete(frame) }
        try {
            session.onForeground()
            val link = withTimeout(2_000) { created.await() }
            withTimeout(2_000) { while (link.onDiagnostic == null) delay(10) }

            val frame = BackendEnvelope(
                id = "d1",
                type = "diag.ping",
                ts = 2,
                trace = TRACE,
                from = "server",
                to = "dev-1",
                task = "task-1",
            )
            requireNotNull(link.onDiagnostic).invoke(frame)

            assertEquals(frame, withTimeout(2_000) { handled.await() })
            assertEquals(1, link.acks.size)
            assertEquals("task-1", link.acks.single().task)
            assertEquals(TRACE, link.acks.single().trace)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `a diagnostic is still acked when the app handler throws`() = runBlocking {
        val settings = FakeSettings().paired("wss://x/v1/gateway")
        val created = CompletableDeferred<FakeLink>()
        val session = session(settings, linkFactory = { credentials, _, _, pushToken, refresh ->
            FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
        })
        session.onDiagnostic = { throw IllegalStateException("boom") }
        try {
            session.onForeground()
            val link = withTimeout(2_000) { created.await() }
            withTimeout(2_000) { while (link.onDiagnostic == null) delay(10) }

            val frame = BackendEnvelope(
                id = "d1",
                type = "diag.ping",
                ts = 2,
                trace = TRACE,
                from = "server",
                to = "dev-1",
                task = "task-1",
            )
            // The app handler's failure propagates (BackendClient.dispatch swallows
            // it in production), but the ack still goes out on the way.
            val thrown = try {
                requireNotNull(link.onDiagnostic).invoke(frame)
                null
            } catch (error: Exception) {
                error
            }
            assertEquals("boom", thrown?.message)
            assertEquals(1, link.acks.size)
            assertEquals("task-1", link.acks.single().task)
            assertEquals(TRACE, link.acks.single().trace)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `a refresh keeps the v1 gateway socket, not the HTTP origin`() = runBlocking {
        val server = pairingServer(MockResponse().setResponseCode(201).setBody(claimResponse()))
        try {
            val settings = FakeSettings().apply {
                seed(
                    url = server.url("/").toString(),
                    device = "d1",
                    household = "h1",
                    accessToken = "a1",
                    refreshToken = "r1",
                    privateKey = "key",
                )
            }

            val refreshed = BackendIdentity(settings) { BackendPairing(it) }.refresh()

            assertNotNull(refreshed)
            assertEquals(
                com.hopa.backend.pairing.BackendUrls.gatewayUrl(server.url("/").toString()),
                refreshed!!.gatewayUrl,
            )
            assertEquals("a1", refreshed.accessToken)
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    }
}
