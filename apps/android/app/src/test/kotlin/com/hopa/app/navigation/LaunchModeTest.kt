package com.hopa.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LaunchModeTest {
    @Test
    fun `no url and no identity is local only with the pairing entry hidden`() {
        val decision = LaunchMode.decide(configuredUrl = null, storedUrl = "", storedDeviceId = "", reachable = false)

        assertEquals(LaunchMode.LocalOnly, decision.mode)
        assertEquals(true, decision.hidePairingEntry)
        assertNull(decision.banner)
    }

    @Test
    fun `configured but unreachable is local only with a banner and not a crash`() {
        val decision = LaunchMode.decide(
            configuredUrl = "https://gateway.example",
            storedUrl = "",
            storedDeviceId = "",
            reachable = false,
        )

        assertEquals(LaunchMode.LocalOnlyUnreachable, decision.mode)
        assertNotNull(decision.banner)
        assertFalse(decision.hidePairingEntry)
    }

    @Test
    fun `configured reachable and no identity asks to sign in`() {
        val decision = LaunchMode.decide(
            configuredUrl = "https://gateway.example",
            storedUrl = "",
            storedDeviceId = "",
            reachable = true,
        )

        assertEquals(LaunchMode.SignIn, decision.mode)
    }

    @Test
    fun `a stored identity is paired`() {
        val decision = LaunchMode.decide(
            configuredUrl = null,
            storedUrl = "wss://gateway.example/v1/gateway",
            storedDeviceId = "device-1",
            reachable = true,
        )

        assertEquals(LaunchMode.Paired, decision.mode)
    }

    @Test
    fun `a stored url that is unreachable is local only with a banner`() {
        val decision = LaunchMode.decide(
            configuredUrl = null,
            storedUrl = "https://gateway.example",
            storedDeviceId = "",
            reachable = false,
        )

        assertEquals(LaunchMode.LocalOnlyUnreachable, decision.mode)
        assertNotNull(decision.banner)
    }
}
